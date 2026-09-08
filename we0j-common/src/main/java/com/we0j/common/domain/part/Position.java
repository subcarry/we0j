package com.we0j.common.domain.part;

/** 0-based 行列坐标（对外工具层转 1-based，FR-085）。 */
public record Position(int line, int character) {}
