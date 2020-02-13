package com.strapdata.strapkop.backup.util;

import lombok.Data;

import java.util.List;

@Data
public class TaskRequestDescription {
    private String snapshotTag;
    private String creationDate;
    private List<String> keyspaces;
    private String table;
}
