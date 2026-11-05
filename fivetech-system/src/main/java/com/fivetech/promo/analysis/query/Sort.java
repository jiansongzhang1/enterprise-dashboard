package com.fivetech.promo.analysis.query;

import java.io.Serializable;

/** 排序：column 列编码，dir asc / desc */
public class Sort implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String column;

    private String dir;

    public String getColumn() { return column; }
    public void setColumn(String column) { this.column = column; }
    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
}
