package com.we0j.llm.spi;

/** 提示词缓存策略（FR-034）。DEFAULT：system 前 2 块 + 末 2 条消息；LAST_USER_ONLY：旁路调用复用主缓存前缀。 */
public enum CacheStrategy { DEFAULT, OFF, LAST_USER_ONLY }
