package com.example.sysfoo.repository;

import com.example.sysfoo.model.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    boolean existsByPostIdAndUsername(Long postId, String username);

    Optional<PostLike> findByPostIdAndUsername(Long postId, String username);

    long countByPostId(Long postId);

    /** Batched equivalent of countByPostId, for a whole page of posts at once — see PostController.getAllPosts(). */
    List<PostLike> findByPostIdIn(List<Long> postIds);

    /** Which of a page of posts the current user has liked — same batching rationale as above. */
    List<PostLike> findByPostIdInAndUsername(List<Long> postIds, String username);
}
