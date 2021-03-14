/*
 * Copyright (C) 2006 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.workflow.support.concurrent;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;

/**
 * Various convenience methods for working with {@link ListenableFuture}s.
 *
 * <p>
 * Mostly copied after Guava's {@code Futures}, because that one is still marked as beta
 * and is subject to change.
 *
 * @author Guava
 */
@Deprecated
public abstract class Futures {
    /**
     * Registers separate success and failure callbacks to be run when the {@code
     * Future}'s computation is {@linkplain java.util.concurrent.Future#isDone()
     * complete} or, if the computation is already complete, immediately.
     *
     * <p>There is no guaranteed ordering of execution of callbacks, but any
     * callback added through this method is guaranteed to be called once the
     * computation is complete.
     *
     * Example: <pre> {@code
     * ListenableFuture<QueryResult> future = ...;
     * addCallback(future,
     *     new FutureCallback<QueryResult> {
     *       public void onSuccess(QueryResult result) {
     *         storeInCache(result);
     *       }
     *       public void onFailure(Throwable t) {
     *         reportError(t);
     *       }
     *     });}</pre>
     *
     * Note: If the callback is slow or heavyweight, consider {@linkplain
     * #addCallback(ListenableFuture, FutureCallback, Executor) supplying an
     * executor}. If you do not supply an executor, {@code addCallback} will use
     * a {@linkplain MoreExecutors#directExecutor direct executor}, which carries
     * some caveats for heavier operations. For example, the callback may run on
     * an unpredictable or undesirable thread:
     *
     * <ul>
     * <li>If the input {@code Future} is done at the time {@code addCallback} is
     * called, {@code addCallback} will execute the callback inline.
     * <li>If the input {@code Future} is not yet done, {@code addCallback} will
     * schedule the callback to be run by the thread that completes the input
     * {@code Future}, which may be an internal system thread such as an RPC
     * network thread.
     * </ul>
     *
     * Also note that, regardless of which thread executes the callback, all
     * other registered but unexecuted listeners are prevented from running
     * during its execution, even if those listeners are to run in other
     * executors.
     *
     * <p>For a more general interface to attach a completion listener to a
     * {@code Future}, see {@link ListenableFuture#addListener addListener}.
     *
     * @param future The future attach the callback to.
     * @param callback The callback to invoke when {@code future} is completed.
     * @since 10.0
     */
    public static <V> void addCallback(ListenableFuture<V> future,
        FutureCallback<? super V> callback) {
      addCallback(future, callback, MoreExecutors.directExecutor());
    }

    /**
     * Registers separate success and failure callbacks to be run when the {@code
     * Future}'s computation is {@linkplain java.util.concurrent.Future#isDone()
     * complete} or, if the computation is already complete, immediately.
     *
     * <p>The callback is run in {@code executor}.
     * There is no guaranteed ordering of execution of callbacks, but any
     * callback added through this method is guaranteed to be called once the
     * computation is complete.
     *
     * Example: <pre> {@code
     * ListenableFuture<QueryResult> future = ...;
     * Executor e = ...
     * addCallback(future, e,
     *     new FutureCallback<QueryResult> {
     *       public void onSuccess(QueryResult result) {
     *         storeInCache(result);
     *       }
     *       public void onFailure(Throwable t) {
     *         reportError(t);
     *       }
     *     });}</pre>
     *
     * When the callback is fast and lightweight, consider {@linkplain
     * #addCallback(ListenableFuture, FutureCallback) omitting the executor} or
     * explicitly specifying {@code directExecutor}. However, be aware of the
     * caveats documented in the link above.
     *
     * <p>For a more general interface to attach a completion listener to a
     * {@code Future}, see {@link ListenableFuture#addListener addListener}.
     *
     * @param future The future attach the callback to.
     * @param callback The callback to invoke when {@code future} is completed.
     * @param executor The executor to run {@code callback} when the future
     *    completes.
     * @since 10.0
     */
    public static <V> void addCallback(final ListenableFuture<V> future,
        final FutureCallback<? super V> callback, Executor executor) {
      Preconditions.checkNotNull(callback);
      Runnable callbackListener = new Runnable() {
        @Override
        public void run() {
          try {
            // TODO(user): (Before Guava release), validate that this
            // is the thing for IE.
            V value = getUninterruptibly(future);
            callback.onSuccess(value);
          } catch (ExecutionException e) {
            callback.onFailure(e.getCause());
          } catch (RuntimeException e) {
            callback.onFailure(e);
          } catch (Error e) {
            callback.onFailure(e);
          }
        }
      };
      future.addListener(callbackListener, executor);
    }

    /**
     * Creates a {@code ListenableFuture} which has its value set immediately upon
     * construction. The getters just return the value. This {@code Future} can't
     * be canceled or timed out and its {@code isDone()} method always returns
     * {@code true}.
     */
    public static <V> ListenableFuture<V> immediateFuture(@Nullable V value) {
      SettableFuture<V> future = SettableFuture.create();
      future.set(value);
      return future;
    }

    /**
     * Returns a {@code ListenableFuture} which has an exception set immediately
     * upon construction.
     *
     * <p>The returned {@code Future} can't be cancelled, and its {@code isDone()}
     * method always returns {@code true}. Calling {@code get()} will immediately
     * throw the provided {@code Throwable} wrapped in an {@code
     * ExecutionException}.
     *
     * @throws Error if the throwable is an {@link Error}.
     */
    public static <V> ListenableFuture<V> immediateFailedFuture(
        Throwable throwable) {
      checkNotNull(throwable);
      SettableFuture<V> future = SettableFuture.create();
      future.setException(throwable);
      return future;
    }

    /**
     * Returns a new {@code ListenableFuture} whose result is asynchronously
     * derived from the result of the given {@code Future}. More precisely, the
     * returned {@code Future} takes its result from a {@code Future} produced by
     * applying the given {@code AsyncFunction} to the result of the original
     * {@code Future}. Example:
     *
     * <pre>   {@code
     *   ListenableFuture<RowKey> rowKeyFuture = indexService.lookUp(query);
     *   AsyncFunction<RowKey, QueryResult> queryFunction =
     *       new AsyncFunction<RowKey, QueryResult>() {
     *         public ListenableFuture<QueryResult> apply(RowKey rowKey) {
     *           return dataService.read(rowKey);
     *         }
     *       };
     *   ListenableFuture<QueryResult> queryFuture =
     *       transform(rowKeyFuture, queryFunction);
     * }</pre>
     *
     * Note: If the derived {@code Future} is slow or heavyweight to create
     * (whether the {@code Future} itself is slow or heavyweight to complete is
     * irrelevant), consider {@linkplain #transform(ListenableFuture,
     * AsyncFunction, Executor) supplying an executor}. If you do not supply an
     * executor, {@code transform} will use a
     * {@linkplain MoreExecutors#directExecutor direct executor}, which carries
     * some caveats for heavier operations. For example, the call to {@code
     * function.apply} may run on an unpredictable or undesirable thread:
     *
     * <ul>
     * <li>If the input {@code Future} is done at the time {@code transform} is
     * called, {@code transform} will call {@code function.apply} inline.
     * <li>If the input {@code Future} is not yet done, {@code transform} will
     * schedule {@code function.apply} to be run by the thread that completes the
     * input {@code Future}, which may be an internal system thread such as an
     * RPC network thread.
     * </ul>
     *
     * Also note that, regardless of which thread executes {@code
     * function.apply}, all other registered but unexecuted listeners are
     * prevented from running during its execution, even if those listeners are
     * to run in other executors.
     *
     * <p>The returned {@code Future} attempts to keep its cancellation state in
     * sync with that of the input future and that of the future returned by the
     * function. That is, if the returned {@code Future} is cancelled, it will
     * attempt to cancel the other two, and if either of the other two is
     * cancelled, the returned {@code Future} will receive a callback in which it
     * will attempt to cancel itself.
     *
     * @param input The future to transform
     * @param function A function to transform the result of the input future
     *     to the result of the output future
     * @return A future that holds result of the function (if the input succeeded)
     *     or the original input's failure (if not)
     * @since 11.0
     */
    public static <I, O> ListenableFuture<O> transform(ListenableFuture<I> input,
            AsyncFunction<? super I, ? extends O> function) {
        return transform(input, function, MoreExecutors.directExecutor());
    }

    /**
     * Returns a new {@code ListenableFuture} whose result is asynchronously
     * derived from the result of the given {@code Future}. More precisely, the
     * returned {@code Future} takes its result from a {@code Future} produced by
     * applying the given {@code AsyncFunction} to the result of the original
     * {@code Future}. Example:
     *
     * <pre>   {@code
     *   ListenableFuture<RowKey> rowKeyFuture = indexService.lookUp(query);
     *   AsyncFunction<RowKey, QueryResult> queryFunction =
     *       new AsyncFunction<RowKey, QueryResult>() {
     *         public ListenableFuture<QueryResult> apply(RowKey rowKey) {
     *           return dataService.read(rowKey);
     *         }
     *       };
     *   ListenableFuture<QueryResult> queryFuture =
     *       transform(rowKeyFuture, queryFunction, executor);
     * }</pre>
     *
     * <p>The returned {@code Future} attempts to keep its cancellation state in
     * sync with that of the input future and that of the future returned by the
     * chain function. That is, if the returned {@code Future} is cancelled, it
     * will attempt to cancel the other two, and if either of the other two is
     * cancelled, the returned {@code Future} will receive a callback in which it
     * will attempt to cancel itself.
     *
     * <p>When the execution of {@code function.apply} is fast and lightweight
     * (though the {@code Future} it returns need not meet these criteria),
     * consider {@linkplain #transform(ListenableFuture, AsyncFunction) omitting
     * the executor} or explicitly specifying {@code directExecutor}.
     * However, be aware of the caveats documented in the link above.
     *
     * @param input The future to transform
     * @param function A function to transform the result of the input future
     *     to the result of the output future
     * @param executor Executor to run the function in.
     * @return A future that holds result of the function (if the input succeeded)
     *     or the original input's failure (if not)
     * @since 11.0
     */
    public static <I, O> ListenableFuture<O> transform(ListenableFuture<I> input,
            AsyncFunction<? super I, ? extends O> function,
            Executor executor) {
        ChainingListenableFuture<I, O> output =
                new ChainingListenableFuture<I, O>(function, input);
        input.addListener(output, executor);
        return output;
    }

    /**
     * Returns a new {@code ListenableFuture} whose result is the product of
     * applying the given {@code Function} to the result of the given {@code
     * Future}. Example:
     *
     * <pre>   {@code
     *   ListenableFuture<QueryResult> queryFuture = ...;
     *   Function<QueryResult, List<Row>> rowsFunction =
     *       new Function<QueryResult, List<Row>>() {
     *         public List<Row> apply(QueryResult queryResult) {
     *           return queryResult.getRows();
     *         }
     *       };
     *   ListenableFuture<List<Row>> rowsFuture =
     *       transform(queryFuture, rowsFunction);
     * }</pre>
     *
     * Note: If the transformation is slow or heavyweight, consider {@linkplain
     * #transform(ListenableFuture, Function, Executor) supplying an executor}.
     * If you do not supply an executor, {@code transform} will use an inline
     * executor, which carries some caveats for heavier operations.  For example,
     * the call to {@code function.apply} may run on an unpredictable or
     * undesirable thread:
     *
     * <ul>
     * <li>If the input {@code Future} is done at the time {@code transform} is
     * called, {@code transform} will call {@code function.apply} inline.
     * <li>If the input {@code Future} is not yet done, {@code transform} will
     * schedule {@code function.apply} to be run by the thread that completes the
     * input {@code Future}, which may be an internal system thread such as an
     * RPC network thread.
     * </ul>
     *
     * Also note that, regardless of which thread executes {@code
     * function.apply}, all other registered but unexecuted listeners are
     * prevented from running during its execution, even if those listeners are
     * to run in other executors.
     *
     * <p>The returned {@code Future} attempts to keep its cancellation state in
     * sync with that of the input future. That is, if the returned {@code Future}
     * is cancelled, it will attempt to cancel the input, and if the input is
     * cancelled, the returned {@code Future} will receive a callback in which it
     * will attempt to cancel itself.
     *
     * <p>An example use of this method is to convert a serializable object
     * returned from an RPC into a POJO.
     *
     * @param input The future to transform
     * @param function A Function to transform the results of the provided future
     *     to the results of the returned future.  This will be run in the thread
     *     that notifies input it is complete.
     * @return A future that holds result of the transformation.
     * @since 9.0 (in 1.0 as {@code compose})
     */
    public static <I, O> ListenableFuture<O> transform(ListenableFuture<I> input,
        final Function<? super I, ? extends O> function) {
      return transform(input, function, MoreExecutors.directExecutor());
    }

    /**
     * Returns a new {@code ListenableFuture} whose result is the product of
     * applying the given {@code Function} to the result of the given {@code
     * Future}. Example:
     *
     * <pre>   {@code
     *   ListenableFuture<QueryResult> queryFuture = ...;
     *   Function<QueryResult, List<Row>> rowsFunction =
     *       new Function<QueryResult, List<Row>>() {
     *         public List<Row> apply(QueryResult queryResult) {
     *           return queryResult.getRows();
     *         }
     *       };
     *   ListenableFuture<List<Row>> rowsFuture =
     *       transform(queryFuture, rowsFunction, executor);
     * }</pre>
     *
     * <p>The returned {@code Future} attempts to keep its cancellation state in
     * sync with that of the input future. That is, if the returned {@code Future}
     * is cancelled, it will attempt to cancel the input, and if the input is
     * cancelled, the returned {@code Future} will receive a callback in which it
     * will attempt to cancel itself.
     *
     * <p>An example use of this method is to convert a serializable object
     * returned from an RPC into a POJO.
     *
     * <p>When the transformation is fast and lightweight, consider {@linkplain
     * #transform(ListenableFuture, Function) omitting the executor} or
     * explicitly specifying {@code directExecutor}. However, be aware of the
     * caveats documented in the link above.
     *
     * @param input The future to transform
     * @param function A Function to transform the results of the provided future
     *     to the results of the returned future.
     * @param executor Executor to run the function in.
     * @return A future that holds result of the transformation.
     * @since 9.0 (in 2.0 as {@code compose})
     */
    public static <I, O> ListenableFuture<O> transform(ListenableFuture<I> input,
        final Function<? super I, ? extends O> function, Executor executor) {
      checkNotNull(function);
      AsyncFunction<I, O> wrapperFunction
          = new AsyncFunction<I, O>() {
              @Override public ListenableFuture<O> apply(I input) {
                O output = function.apply(input);
                return immediateFuture(output);
              }
          };
      return transform(input, wrapperFunction, executor);
    }

    /**
     * Creates a new {@code ListenableFuture} whose value is a list containing the
     * values of all its input futures, if all succeed. If any input fails, the
     * returned future fails.
     *
     * <p>The list of results is in the same order as the input list.
     *
     * <p>Canceling this future does not cancel any of the component futures;
     * however, if any of the provided futures fails or is canceled, this one is,
     * too.
     *
     * @param futures futures to combine
     * @return a future that provides a list of the results of the component
     *         futures
     * @since 10.0
     */
    @Beta
    public static <V> ListenableFuture<List<V>> allAsList(
        ListenableFuture<? extends V>... futures) {
      return new ListFuture<V>(ImmutableList.copyOf(futures), true,
          MoreExecutors.directExecutor());
    }

    /**
     * Creates a new {@code ListenableFuture} whose value is a list containing the
     * values of all its input futures, if all succeed. If any input fails, the
     * returned future fails.
     *
     * <p>The list of results is in the same order as the input list.
     *
     * <p>Canceling this future does not cancel any of the component futures;
     * however, if any of the provided futures fails or is canceled, this one is,
     * too.
     *
     * @param futures futures to combine
     * @return a future that provides a list of the results of the component
     *         futures
     * @since 10.0
     */
    @Beta
    public static <V> ListenableFuture<List<V>> allAsList(
        Iterable<? extends ListenableFuture<? extends V>> futures) {
      return new ListFuture<V>(ImmutableList.copyOf(futures), true,
          MoreExecutors.directExecutor());
    }
}
