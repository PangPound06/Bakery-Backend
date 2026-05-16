package com.app.my_project.repository;

import com.app.my_project.entity.TableReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface TableReservationRepository extends JpaRepository<TableReservationEntity, Long> {

    // ดึงการจองทั้งหมดของ user
    List<TableReservationEntity> findByEmailOrderByReservationDateDescReservationTimeDesc(String email);

    // ดึงทุกการจอง (admin)
    List<TableReservationEntity> findAllByOrderByReservationDateDescReservationTimeDesc();

    // ดึงตาม reservationCode
    Optional<TableReservationEntity> findByReservationCode(String reservationCode);

    // ตรวจสอบว่าโต๊ะถูกจองแล้วในช่วงเวลานั้นหรือยัง
    // (ไม่นับที่ถูก cancelled)
    @Query("""
        SELECT r FROM TableReservationEntity r
        WHERE r.tableNo = :tableNo
          AND r.reservationDate = :date
          AND r.reservationTime = :time
          AND r.status <> 'cancelled'
    """)
    List<TableReservationEntity> findConflicting(
        @Param("tableNo") String tableNo,
        @Param("date") LocalDate date,
        @Param("time") LocalTime time
    );

    // ดึงการจองตามวันที่ (admin: ดูตารางประจำวัน)
    List<TableReservationEntity> findByReservationDateOrderByReservationTimeAsc(LocalDate date);

    // ดึงการจองตามโต๊ะ + วันที่
    List<TableReservationEntity> findByTableNoAndReservationDateOrderByReservationTimeAsc(
        String tableNo, LocalDate date
    );
}