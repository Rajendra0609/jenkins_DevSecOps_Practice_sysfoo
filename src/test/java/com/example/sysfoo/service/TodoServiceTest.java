package com.example.sysfoo.service;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.service.TodoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @InjectMocks
    private TodoService todoService;

    @Test
    public void saveTodoTest() {
        Todo todo = new Todo("Test Todo");
        when(todoRepository.save(todo)).thenReturn(todo);

        Todo savedTodo = todoService.save(todo);
        assertEquals("Test Todo", savedTodo.getText());
    }
}
