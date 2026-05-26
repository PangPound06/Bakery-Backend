package com.app.my_project.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * เพิ่มจากเดิม:
 * - @Index บนคอลัมน์ที่ query บ่อย:
 * - email (findByEmailOrderByReservationDateDesc...)
 * - reservationDate (filter ตามวัน)
 * - tableNo (availability check)
 * - status (filter cancelled/active)
 * - reservationCode (search)
 * - @Column(unique = true) ที่ reservationCode (กัน duplicate code)
 */
@Entity
@Table(name = "tb_reservations", indexes = {
        @Index(name = "idx_reservations_email", columnList = "email"),
        @Index(name = "idx_reservations_date", columnList = "reservation_date"),
        @Index(name = "idx_reservations_table_date", columnList = "table_no, reservation_date"),
        @Index(name = "idx_reservations_status", columnList = "status"),
        @Index(name = "idx_reservations_code", columnList = "reservation_code")
})
public class TableReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    private String tableNo;
    private LocalDate reservationDate;
    private LocalTime reservationTime;
    private Integer partySize;
    private String customerName;
    private String customerPhone;
    private String note;

    // pending / confirmed / cancelled / completed
    private String status = "pending";

    @Column(unique = true)
    private String reservationCode;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== Getters & Setters =====
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getTableNo() {
        return tableNo;
    }

    public void setTableNo(String tableNo) {
        this.tableNo = tableNo;
    }

    public LocalDate getReservationDate() {
        return reservationDate;
    }

    public void setReservationDate(LocalDate reservationDate) {
        this.reservationDate = reservationDate;
    }

    public LocalTime getReservationTime() {
        return reservationTime;
    }

    public void setReservationTime(LocalTime reservationTime) {
        this.reservationTime = reservationTime;
    }

    public Integer getPartySize() {
        return partySize;
    }

    public void setPartySize(Integer partySize) {
        this.partySize = partySize;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReservationCode() {
        return reservationCode;
    }

    public void setReservationCode(String reservationCode) {
        this.reservationCode = reservationCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
