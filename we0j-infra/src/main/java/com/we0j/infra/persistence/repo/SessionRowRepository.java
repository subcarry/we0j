package com.we0j.infra.persistence.repo;

import com.we0j.infra.persistence.entity.SessionRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Session 行仓库（DDD §4.5.2，FR-09 / FR-013 / FR-014）。
 * 查询语义对齐 v_session_latest 视图：/resume 列表排除 incognito 子会话。
 */
public interface SessionRowRepository extends JpaRepository<SessionRow, String> {

    /** /resume 分页列表：按 projectId、排除 incognito、最近更新优先。 */
    List<SessionRow> findByProjectIdAndIncognitoFalseOrderByTimeUpdatedDesc(String projectId, Pageable page);

    /** 归属校验：跨项目访问 session 一律视为不存在（安全边界）。 */
    Optional<SessionRow> findByIdAndProjectId(String id, String projectId);

    /** 标题模糊搜索（大小写不敏感）。 */
    @Query("select s from SessionRow s where s.projectId=:pid and s.incognito=false "
            + "and lower(s.title) like lower(concat('%',:kw,'%')) order by s.timeUpdated desc")
    List<SessionRow> search(@Param("pid") String projectId, @Param("kw") String keyword, Pageable page);
}
