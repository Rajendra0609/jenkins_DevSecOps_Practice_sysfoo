package com.example.sysfoo.service;

import com.example.sysfoo.model.Post;
import com.example.sysfoo.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    @InjectMocks
    private PostService postService;

    @Test
    public void savePostTest() {
        Post post = new Post("Watering hole hours", "Open from dawn to dusk.", null, "Rafiki");
        when(postRepository.save(post)).thenReturn(post);

        Post saved = postService.save(post);
        assertEquals("Watering hole hours", saved.getTitle());
        assertEquals("Rafiki", saved.getAuthor());
    }

    @Test
    public void findAllNewestFirstTest() {
        Post p1 = new Post("First", "Body 1", null, "Author A");
        Post p2 = new Post("Second", "Body 2", "https://example.com/img.jpg", "Author B");
        when(postRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p2, p1));

        List<Post> result = postService.findAllNewestFirst();
        assertEquals(2, result.size());
        assertEquals("Second", result.get(0).getTitle());
    }
}
