package com.example.sysfoo.repository;

import com.example.sysfoo.model.IssueLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface IssueLinkRepository extends JpaRepository<IssueLink, Long> {

    List<IssueLink> findBySourceIdOrTargetId(Long sourceId, Long targetId);

    List<IssueLink> findBySourceIdInOrTargetIdIn(Collection<Long> sourceIds, Collection<Long> targetIds);

    boolean existsBySourceIdAndTargetIdAndLinkType(Long sourceId, Long targetId, String linkType);
}
