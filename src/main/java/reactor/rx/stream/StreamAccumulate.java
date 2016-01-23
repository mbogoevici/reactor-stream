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
package reactor.rx.stream;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.trait.Completable;
import reactor.core.trait.Connectable;
import reactor.core.trait.Subscribable;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;
import reactor.fn.BiFunction;

/**
 * Accumulates the source values with an accumulator function and
 * returns the intermediate results of this function.
 * <p>
 * Unlike {@link StreamScan}, this operator doesn't take an initial value
 * but treats the first source value as initial value.
 * <br>
 * The accumulation works as follows:
 * <pre><code>
 * result[0] = accumulator(source[0], source[1])
 * result[1] = accumulator(result[0], source[2])
 * result[2] = accumulator(result[1], source[3])
 * ...
 * </code></pre>
 *
 * @param <T> the input and accumulated value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamAccumulate<T> extends StreamBarrier<T, T> {

	final BiFunction<T, ? super T, T> accumulator;

	public StreamAccumulate(Publisher<? extends T> source, BiFunction<T, ? super T, T> accumulator) {
		super(source);
		this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new AccumulateSubscriber<>(s, accumulator));
	}

	static final class AccumulateSubscriber<T> implements Subscriber<T>, Subscribable, Completable, Connectable {
		final Subscriber<? super T> actual;

		final BiFunction<T, ? super T, T> accumulator;

		Subscription s;

		T value;

		boolean done;

		public AccumulateSubscriber(Subscriber<? super T> actual, BiFunction<T, ? super T, T> accumulator) {
			this.actual = actual;
			this.accumulator = accumulator;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(s);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Exceptions.onNextDropped(t);
				return;
			}

			T v = value;

			if (v != null) {
				try {
					t = accumulator.apply(v, t);
				} catch (Throwable e) {
					s.cancel();
					Exceptions.throwIfFatal(e);
					onError(Exceptions.unwrap(e));
					return;
				}
				if (t == null) {
					s.cancel();

					onError(new NullPointerException("The accumulator returned a null value"));
					return;
				}
			}
			value = t;
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Exceptions.onErrorDropped(t);
				return;
			}
			done = true;
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			actual.onComplete();
		}

		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public Object connectedInput() {
			return accumulator;
		}

		@Override
		public Object connectedOutput() {
			return value;
		}

		@Override
		public Object upstream() {
			return s;
		}
	}
}
