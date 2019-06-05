package com.strapdata.strapkop.watch;

import javax.annotation.Nullable;

public class WatchEvent<T> {
    public T t;

    private WatchEvent(T t) {
        this.t = t;
    }

    public interface IAdded<T> {}
    public interface IModified<T> {}
    public interface IDeleted<T> {}

    public static class Added<T> extends WatchEvent<T> implements IAdded<T> {
        public Added(final T t) {
            super(t);
        }
    }

    public static class Modified<T> extends WatchEvent<T> implements IModified<T> {
        public final T old;

        public Modified(@Nullable final T old,  final T t) {
            super(t);
            this.old = old;
        }
    }

    public static class Deleted<T> extends WatchEvent<T> implements IDeleted<T> {
        public Deleted(final T t) {
            super(t);
        }
    }
}
