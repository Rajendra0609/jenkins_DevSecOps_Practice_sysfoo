package com.example.sysfoo.service;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TodoServiceTest {

    // BUG FIX: this was `import com.example.sysfoo.service.TodoService;` inside
    // the com.example.sysfoo.service package itself — a redundant self-import.
    // It compiled fine but is exactly the kind of dead-code smell a SonarQube
    // pass flags; removed.

    @Mock
    private TodoRepository todoRepository;

    @InjectMocks
    private TodoService todoService;

    @Test
    public void saveTodoTest() {
        Todo todo = new Todo("Alice", "Test Todo");
        when(todoRepository.save(todo)).thenReturn(todo);

        Todo savedTodo = todoService.save(todo);
        assertEquals("Test Todo", savedTodo.getText());
        assertEquals("Alice", savedTodo.getName());
    }

    @Test
    public void saveTodoWithoutNameTest() {
        Todo todo = new Todo("Test Todo");
        when(todoRepository.save(todo)).thenReturn(todo);

        Todo savedTodo = todoService.save(todo);
        assertEquals("Test Todo", savedTodo.getText());
    }

    // BUG FIX: findById/delete didn't exist on TodoService before this pass —
    // covering them here since they're what makes editing/completing/deleting
    // a task actually persist (see TodoController).

    @Test
    public void findByIdTest() {
        Todo todo = new Todo("Alice", "Test Todo");
        todo.setId(1L);
        when(todoRepository.findById(1L)).thenReturn(Optional.of(todo));

        Optional<Todo> found = todoService.findById(1L);
        assertTrue(found.isPresent());
        assertEquals("Test Todo", found.get().getText());
    }

    @Test
    public void findByIdNotFoundTest() {
        when(todoRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<Todo> found = todoService.findById(99L);
        assertTrue(found.isEmpty());
    }

    @Test
    public void deleteExistingTodoTest() {
        when(todoRepository.existsById(1L)).thenReturn(true);

        boolean deleted = todoService.delete(1L);
        assertTrue(deleted);
    }

    @Test
    public void deleteMissingTodoTest() {
        when(todoRepository.existsById(99L)).thenReturn(false);

        boolean deleted = todoService.delete(99L);
        assertFalse(deleted);
    }
}
