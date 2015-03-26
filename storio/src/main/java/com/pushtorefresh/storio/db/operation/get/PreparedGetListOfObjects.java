package com.pushtorefresh.storio.db.operation.get;

import android.database.Cursor;
import android.support.annotation.NonNull;

import com.pushtorefresh.storio.db.Changes;
import com.pushtorefresh.storio.db.StorIODb;
import com.pushtorefresh.storio.db.query.Query;
import com.pushtorefresh.storio.db.query.RawQuery;
import com.pushtorefresh.storio.operation.MapFunc;
import com.pushtorefresh.storio.operation.PreparedOperationWithReactiveStream;
import com.pushtorefresh.storio.util.EnvironmentUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;

/**
 * Represents an Operation for {@link StorIODb} which performs query that retrieves data as list of objects
 * from {@link StorIODb}
 *
 * @param <T> type of result
 */
public class PreparedGetListOfObjects<T> extends PreparedGet<List<T>> {

    @NonNull
    private final MapFunc<Cursor, T> mapFunc;

    PreparedGetListOfObjects(@NonNull StorIODb storIODb, @NonNull Query query, @NonNull GetResolver getResolver, @NonNull MapFunc<Cursor, T> mapFunc) {
        super(storIODb, query, getResolver);
        this.mapFunc = mapFunc;
    }

    PreparedGetListOfObjects(@NonNull StorIODb storIODb, @NonNull RawQuery rawQuery, @NonNull GetResolver getResolver, @NonNull MapFunc<Cursor, T> mapFunc) {
        super(storIODb, rawQuery, getResolver);
        this.mapFunc = mapFunc;
    }

    /**
     * Executes Prepared Operation immediately in current thread
     *
     * @return non-null list with mapped results, can be empty
     */
    @SuppressWarnings("TryFinallyCanBeTryWithResources") // Min SDK :(
    @NonNull
    public List<T> executeAsBlocking() {
        final Cursor cursor;

        if (query != null) {
            cursor = getResolver.performGet(storIODb, query);
        } else if (rawQuery != null) {
            cursor = getResolver.performGet(storIODb, rawQuery);
        } else {
            throw new IllegalStateException("Please specify query");
        }

        try {
            final List<T> list = new ArrayList<>(cursor.getCount());

            while (cursor.moveToNext()) {
                list.add(mapFunc.map(cursor));
            }

            return list;
        } finally {
            cursor.close();
        }
    }

    /**
     * Creates an {@link Observable} which will emit result of operation
     *
     * @return non-null {@link Observable} which will emit non-null list with mapped results, list can be empty
     */
    @NonNull
    public Observable<List<T>> createObservable() {
        EnvironmentUtil.throwExceptionIfRxJavaIsNotAvailable("createObservable()");

        return Observable.create(new Observable.OnSubscribe<List<T>>() {
            @Override
            public void call(Subscriber<? super List<T>> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onNext(executeAsBlocking());
                    subscriber.onCompleted();
                }
            }
        });
    }

    /**
     * Creates an {@link Observable} which will be subscribed to changes of query tables
     * and will emit result each time change occurs
     * <p/>
     * First result will be emitted immediately,
     * other emissions will occur only if changes of query tables will occur
     *
     * @return non-null {@link Observable} which will emit non-null list with mapped results and will be subscribed to changes of query tables
     */
    @NonNull
    @Override
    public Observable<List<T>> createObservableStream() {
        EnvironmentUtil.throwExceptionIfRxJavaIsNotAvailable("createObservableStream()");

        final Set<String> tables;

        if (query != null) {
            tables = Collections.singleton(query.table);
        } else if (rawQuery != null) {
            tables = rawQuery.affectedTables;
        } else {
            throw new IllegalStateException("Please specify query");
        }

        if (tables != null && !tables.isEmpty()) {
            return storIODb
                    .observeChangesInTables(tables)
                    .map(new Func1<Changes, List<T>>() { // each change triggers executeAsBlocking
                        @Override
                        public List<T> call(Changes affectedTables) {
                            return executeAsBlocking();
                        }
                    })
                    .startWith(executeAsBlocking()); // start stream with first query result
        } else {
            return createObservable();
        }
    }

    public static class Builder<T> {

        @NonNull
        private final StorIODb storIODb;

        @NonNull
        private final Class<T> type; // currently type not used as object, only for generic Builder class

        private MapFunc<Cursor, T> mapFunc;
        private Query query;
        private RawQuery rawQuery;
        private GetResolver getResolver;

        Builder(@NonNull StorIODb storIODb, @NonNull Class<T> type) {
            this.storIODb = storIODb;
            this.type = type;
        }

        /**
         * Specifies map function for Get Operation which will map {@link Cursor} to object of required type
         *
         * @param mapFunc map function which will map {@link Cursor} to object of required type
         * @return builder
         */
        @NonNull
        public Builder<T> withMapFunc(@NonNull MapFunc<Cursor, T> mapFunc) {
            this.mapFunc = mapFunc;
            return this;
        }

        /**
         * Specifies {@link Query} for Get Operation
         *
         * @param query query
         * @return builder
         */
        @NonNull
        public Builder<T> withQuery(@NonNull Query query) {
            this.query = query;
            return this;
        }

        /**
         * Specifies {@link RawQuery} for Get Operation, you can use it for "joins" and same constructions which are not allowed in {@link Query}
         *
         * @param rawQuery query
         * @return builder
         */
        @NonNull
        public Builder<T> withQuery(@NonNull RawQuery rawQuery) {
            this.rawQuery = rawQuery;
            return this;
        }

        /**
         * Optional: Specifies {@link GetResolver} for Get Operation which allows you to customize behavior of Get Operation
         *
         * @param getResolver get resolver
         * @return builder
         */
        @NonNull
        public Builder<T> withGetResolver(@NonNull GetResolver getResolver) {
            this.getResolver = getResolver;
            return this;
        }

        /**
         * Prepares Get Operation
         *
         * @return {@link PreparedGetListOfObjects} instance
         */
        @NonNull
        public PreparedOperationWithReactiveStream<List<T>> prepare() {
            if (getResolver == null) {
                getResolver = DefaultGetResolver.INSTANCE;
            }

            if (mapFunc == null) {
                throw new IllegalStateException("Please specify map function");
            }

            if (query != null) {
                return new PreparedGetListOfObjects<>(storIODb, query, getResolver, mapFunc);
            } else if (rawQuery != null) {
                return new PreparedGetListOfObjects<>(storIODb, rawQuery, getResolver, mapFunc);
            } else {
                throw new IllegalStateException("Please specify query");
            }
        }
    }
}
