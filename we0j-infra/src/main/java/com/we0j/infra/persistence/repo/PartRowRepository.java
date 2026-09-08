package com.we0j.infra.persistence.repo;

import com.we0j.infra.persistence.entity.PartRow;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Part 行仓库（DDD §4.5.2）。
 * 写入主路径是 {@code PartWriter} 的原生 upsert；本接口承担读取与批量删除。
 */
public interface PartRowRepository extends JpaRepository<PartRow, String> {

    List<PartRow> findBySessionIdOrderByTimeCreatedAsc(String sessionId);

    List<PartRow> findByMessageIdOrderByTimeCreatedAsc(String messageId);

    /** 按冗余 type 列过滤（如 type='tool' 的悬挂扫描，NFR-03）。 */
    List<PartRow> findBySessionIdAndType(String sessionId, String type);

    void deleteByMessageIdIn(Collection<String> messageIds);

    void deleteBySessionId(String sessionId);
}
