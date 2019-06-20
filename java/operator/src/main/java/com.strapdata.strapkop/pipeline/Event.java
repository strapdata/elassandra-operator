package com.strapdata.strapkop.pipeline;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
public class Event<KeyT, DataT> {
    KeyT key;
    DataT data;
    
    public KeyT getKey() {
        return this.key;
    }
    
    public DataT getData() {
        return this.data;
    }
    
    public Event<KeyT, DataT> setKey(KeyT key) {
        this.key = key;
        return this;
    }
    
    public Event<KeyT, DataT> setData(DataT data) {
        this.data = data;
        return this;
    }
    
    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Event)) return false;
        final Event<?, ?> other = (Event<?, ?>) o;
        if (!other.canEqual((Object) this)) return false;
        final Object this$key = this.getKey();
        final Object other$key = other.getKey();
        if (this$key == null ? other$key != null : !this$key.equals(other$key)) return false;
        final Object this$data = this.getData();
        final Object other$data = other.getData();
        if (this$data == null ? other$data != null : !this$data.equals(other$data)) return false;
        return true;
    }
    
    protected boolean canEqual(final Object other) {
        return other instanceof Event;
    }
    
    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $key = this.getKey();
        result = result * PRIME + ($key == null ? 43 : $key.hashCode());
        final Object $data = this.getData();
        result = result * PRIME + ($data == null ? 43 : $data.hashCode());
        return result;
    }
    
    public String toString() {
        return "Event(key=" + this.getKey() + ", data=" + this.getData() + ")";
    }
}
