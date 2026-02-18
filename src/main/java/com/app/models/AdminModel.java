package com.app.models;

public class AdminModel {
    private Integer id;
    private String email;
    private String password;
    private String fullName;
    private String role;        // staff, admin, super_admin
    private String department;

    // ===== Constructors =====
    public AdminModel() {}

    public AdminModel(Integer id, String email, String password, String fullName, String role, String department) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
        this.department = department;
    }

    // ===== Getters =====
    public Integer getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public String getDepartment() {
        return department;
    }

    // ===== Setters =====
    public void setId(Integer id) {
        this.id = id;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
