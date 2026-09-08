package com.we0j.server.dto;

import java.util.List;

/** 游标分页响应（§8.1：nextCursor = 末条 timeUpdated(epoch ms)，客户端回传 before=）。 */
public record ListPage<T>(List<T> items, Long nextCursor) {

    public ListPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
