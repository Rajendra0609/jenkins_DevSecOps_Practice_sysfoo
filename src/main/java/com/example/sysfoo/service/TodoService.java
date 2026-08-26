package com.example.sysfoo.service;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.TodoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TodoService {

    @Autowired
    private TodoRepository todoRepository;

    public Todo save(Todo todo) {
        return todoRepository.save(todo);
    }

    public List<Todo> findAll() {
        return todoRepository.findAll();
    }

    public List<Todo> findAllNewestFirst() {
        return todoRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Todo> findById(Long id) {
        return todoRepository.findById(id);
    }

    /**
     * BUG FIX: there was previously no way to remove a Todo from the database
     * at all — TodoController had no delete endpoint, so the frontend's
     * "Remove" / "Clear Done" / "Clear All" actions only ever touched the
     * in-browser array. Every "deleted" task reappeared on the next reload.
     *
     * @return true if a row existed and was deleted, false if id was unknown.
     */
    public boolean delete(Long id) {
        if (!todoRepository.existsById(id)) {
            return false;
        }
        todoRepository.deleteById(id);
        return true;
    }
}
