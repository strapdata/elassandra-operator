package com.strapdata.strapkop.controllers;

public interface Controller<DataT> {
    void accept(DataT data) throws Exception;
}
