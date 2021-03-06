/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rx;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.SchedulerGroup;
import reactor.core.queue.QueueSupplier;
import reactor.core.state.Completable;
import reactor.core.state.Introspectable;
import reactor.core.timer.Timer;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.CancelledSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;
import reactor.rx.subscriber.SerializedSubscriber;

/**
 * Broadcaster is an identity {@link Processor} extending Stream. The message passing strategy can be chosen over the
 * various factories including {@link #replay replaying} or {@link #blocking}.
 * <p>
 *
 * Broadcasters are akin to Reactive Extensions Subjects. Some broadcasters might be shared and will require
 * serialization as
 * onXXXX handle should not be invoke concurrently. }
 * {@link #serialize} can take care of this specific issue. Excluding arbitrary {@link Processor} , the only
 * unicast restricted {@link Broadcaster} are {@link #blocking()} and {@link #unicast()}.
 *
 * @author Stephane Maldini
 */
public class Broadcaster<O> extends StreamProcessor<O, O> {

	/**
	 * @param <IN>
	 * @return
	 */
	public static <IN> Broadcaster<IN> async(final SchedulerGroup group) {
		FluxProcessor<IN, IN> emitter = EmitterProcessor.create();
		return new Broadcaster<>(emitter, emitter.dispatchOn(group), null, false);
	}

	/**
	 * @param <IN>
	 * @return
	 */
	public static <IN> Broadcaster<IN> blocking() {
		FluxProcessor<IN, IN> emitter = FluxProcessor.blocking();
		return new Broadcaster<>(emitter, emitter, null, false);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link reactor.rx
	 * .Broadcaster#onNext(Object)}, {@link Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values
	 * broadcasted are directly consumable by subscribing to the returned instance.
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> create() {
		return create(null, false);
	}


	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link reactor.rx
	 * .Broadcaster#onNext(Object)}, {@link Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values
	 * broadcasted are directly consumable by subscribing to the returned instance.
	 * @param autoCancel Propagate cancel upstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> create(boolean autoCancel) {
		return create(null, autoCancel);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param timer the Reactor {@link Timer} to use downstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> create(Timer timer) {
		return create(timer, false);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param timer the Reactor {@link Timer} to use downstream
	 * @param autoCancel Propagate cancel upstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> create(Timer timer, boolean autoCancel) {
		return from(EmitterProcessor.<T>create(autoCancel), timer, autoCancel);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance. <p> Will not bubble up  any {@link Exceptions.CancelException}
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> unicast() {
		return unicast(null);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance. <p> Will not bubble up  any {@link Exceptions.CancelException}
	 * @param timer the Reactor {@link Timer} to use downstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> unicast(Timer timer) {
		return from(new UnicastProcessor<>(QueueSupplier.<T>small(true).get()), timer, true);
	}

	/**
	 * Build a {@literal Broadcaster} that will support concurrent signals (onNext, onError, onComplete) and use
	 * thread-stealing to serialize underlying emitter processor calls.
	 *
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> serialize() {
		return serialize(null);
	}

	/**
	 * Build a {@literal Broadcaster} that will support concurrent signals (onNext, onError, onComplete) and use
	 * thread-stealing to serialize underlying emitter processor calls.
	 *
	 * @param timer the Reactor {@link Timer} to use downstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> serialize(Timer timer) {
		Processor<T, T> processor = EmitterProcessor.create();
		return new Broadcaster<T>(SerializedSubscriber.create(processor), processor, timer, true);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param emitter Identity processor to support broadcasting
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> from(Processor<T, T> emitter) {
		return from(emitter, null, false);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param emitter Identity processor to support broadcasting
	 * @param autoCancel Propagate cancel upstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> from(Processor<T, T> emitter, boolean autoCancel) {
		return from(emitter, null, autoCancel);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param timer the Reactor {@link Timer} to use downstream
	 * @param autoCancel Propagate cancel upstream
	 * @param emitter Identity processor to support broadcasting
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> from(Processor<T, T> emitter, Timer timer, boolean autoCancel) {
		return new Broadcaster<T>(emitter, timer, autoCancel);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replay() {
		return replay(null);
	}

	/**
	 * Build a {@literal Broadcaster}, ready to broadcast values with {@link Broadcaster#onNext(Object)}, {@link
	 * Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}. Values broadcasted are directly consumable by
	 * subscribing to the returned instance.
	 * @param timer the Reactor {@link Timer} to use downstream
	 * @param <T> the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replay(Timer timer) {
		return new Broadcaster<T>(EmitterProcessor.<T>replay(), timer, false);
	}

	/**
	 * Build a {@literal Broadcaster}, rfirst broadcasting the most recent signal then starting with the passed value,
	 * then ready to broadcast values with {@link reactor.rx
	 * .Broadcaster#onNext(Object)},
	 * {@link Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete
	 * ()}.
	 * Values broadcasted are directly consumable by subscribing to the returned instance.
	 * <p>
	 * A serialized broadcaster will make sure that even in a multhithreaded scenario, only one thread will be able to
	 * broadcast at a time.
	 * The synchronization is non blocking for the publisher, using thread-stealing and first-in-first-served patterns.
	 *
	 * @param value the value to start with the sequence
	 * @param <T> the type of values passing through the {@literal action}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replayLastOrDefault(T value) {
		return replayLastOrDefault(value, null);
	}

	/**
	 * Build a {@literal Broadcaster}, first broadcasting the most recent signal then ready to broadcast values with
	 * {@link #onNext(Object)},
	 * {@link Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}.
	 * Values broadcasted are directly consumable by subscribing to the returned instance.
	 *
	 * @param timer the {@link Timer} to use downstream
	 * @param <T>        the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replayLast(Timer timer) {
		return replayLastOrDefault(null, timer);
	}

	/**
	 * Build a {@literal Broadcaster}, first broadcasting the most recent signal then starting with the passed value,
	 * then  ready to broadcast values with {@link #onNext(Object)},
	 * {@link Broadcaster#onError(Throwable)}, {@link Broadcaster#onComplete()}.
	 * Values broadcasted are directly consumable by subscribing to the returned instance.
	 *
	 * @param value the value to start with the sequence
	 * @param timer the {@link Timer} to use downstream
	 * @param <T>        the type of values passing through the {@literal Broadcaster}
	 * @return a new {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replayLastOrDefault(T value, Timer timer) {
		Broadcaster<T> b = new Broadcaster<T>(EmitterProcessor.<T>replay(1), timer, false);
		if(value != null){
			b.onNext(value);
		}
		return b;
	}

	/**
	 * INTERNAL
	 */

	final Timer            timer;
	final boolean          ignoreDropped;
	final SwapSubscription subscription;

	protected Broadcaster(Processor<O, O> processor, Timer timer, boolean ignoreDropped) {
		this(processor, processor, timer, ignoreDropped);
	}

	protected Broadcaster(
			Subscriber<O> receiver,
			Publisher<O> publisher,
			Timer timer,
			boolean ignoreDropped) {
		super(receiver, publisher);
		this.timer = timer;
		this.ignoreDropped = ignoreDropped;
		this.subscription = SwapSubscription.create();

		receiver.onSubscribe(subscription);
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription.swapTo(subscription);
	}

	@Override
	public void onNext(O ev) {
		try {
			if(subscription.isCancelled()){
				Exceptions.onNextDropped(ev);
			}
			subscription.ack();
			receiver.onNext(ev);
		}
		catch (Exceptions.InsufficientCapacityException | Exceptions.CancelException c) {
			if (!ignoreDropped) {
				throw c;
			}
		}
	}

	@Override
	public void onError(Throwable t) {
		try {
			receiver.onError(t);
		}
		catch (Exceptions.InsufficientCapacityException | Exceptions.CancelException c) {
			//IGNORE
		}
	}

	@Override
	public void onComplete() {
		try {
			receiver.onComplete();
		}
		catch (Exceptions.InsufficientCapacityException | Exceptions.CancelException c) {
			//IGNORE
		}
	}

	@Override
	public Timer getTimer() {
		return timer != null ? timer : Timer.globalOrNull();
	}

	static final class SwapSubscription implements Subscription, Receiver, Completable, Introspectable {

		@SuppressWarnings("unused")
		volatile Subscription subscription;
		static final AtomicReferenceFieldUpdater<SwapSubscription, Subscription> SUBSCRIPTION =
				PlatformDependent.newAtomicReferenceFieldUpdater(SwapSubscription.class, "subscription");


		@SuppressWarnings("unused")
		volatile long requested;
		protected static final AtomicLongFieldUpdater<SwapSubscription> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(SwapSubscription.class, "requested");

		public static SwapSubscription create() {
			return new SwapSubscription();
		}

		SwapSubscription() {
			SUBSCRIPTION.lazySet(this, EmptySubscription.INSTANCE);
		}

		/**
		 *
		 * @param subscription
		 */
		public void swapTo(Subscription subscription) {
			Subscription old = SUBSCRIPTION.getAndSet(this, subscription);
			if(old != EmptySubscription.INSTANCE){
				subscription.cancel();
				return;
			}
			long r = REQUESTED.getAndSet(this, 0L);
			if(r != 0L){
				subscription.request(r);
			}
		}

		/**
		 *
		 * @return
		 */
		public boolean isUnsubscribed(){
			return subscription == EmptySubscription.INSTANCE;
		}

		/**
		 *
		 * @param l
		 * @return
		 */
		public boolean ack(long l) {
			return BackpressureUtils.getAndSub(REQUESTED, this, l) >= l;
		}

		/**
		 *
		 * @return
		 */
		public boolean ack(){
			return BackpressureUtils.getAndSub(REQUESTED, this, 1L) != 0;
		}

		/**
		 *
		 * @return
		 */
		public boolean isCancelled(){
			return subscription == CancelledSubscription.INSTANCE;
		}

		@Override
		public void request(long n) {
			BackpressureUtils.getAndAdd(REQUESTED, this, n);
			SUBSCRIPTION.get(this)
			            .request(n);
		}

		@Override
		public void cancel() {
			Subscription s;
			for(;;) {
				s = subscription;
				if(s == CancelledSubscription.INSTANCE || s == EmptySubscription.INSTANCE){
					return;
				}

				if(SUBSCRIPTION.compareAndSet(this, s, CancelledSubscription.INSTANCE)){
					s.cancel();
					break;
				}
			}
		}

		@Override
		public Object upstream() {
			return subscription;
		}

		@Override
		public boolean isStarted() {
			return !isUnsubscribed();
		}

		@Override
		public boolean isTerminated() {
			return isUnsubscribed();
		}

		@Override
		public int getMode() {
			return 0;
		}

		@Override
		public String getName() {
			return null;
		}

		@Override
		public String toString() {
			return "SwapSubscription{" +
					"subscription=" + subscription +
					", requested=" + requested +
					'}';
		}
	}
}
