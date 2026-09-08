package com.we0j.infra.persistence.repo;

import com.we0j.infra.persistence.entity.MessageRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Message 行仓库（DDD §4.5.2）。 */
public interface MessageRowRepository extends JpaRepository<MessageRow, String> {

    /** 历史装载（resume）：会话内按创建时间升序。 */
    List<MessageRow> findBySessionIdOrderByTimeCreatedAsc(String sessionId);

    /** 会话级联清理（外键 ON DELETE CASCADE 之外的应用层显式删除路径）。 */
    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);
}
