package com.example.sysfoo.model;

import jakarta.persistence.*;

@Entity
@Table(name = "ticket_types", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class TicketType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    public TicketType() {
    }

    public TicketType(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim().toUpperCase();
    }
}
