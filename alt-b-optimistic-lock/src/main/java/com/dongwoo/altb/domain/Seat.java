package com.dongwoo.altb.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대안 B — 낙관적 락 (@Version).
 *
 * JPA가 UPDATE 시 version 컬럼을 자동으로 WHERE 절에 끼워넣는다:
 *   UPDATE seat SET status=?, version=version+1, ... WHERE id=? AND version=?
 *
 * 동시 100건 요청 시 1건만 통과하고 나머지는 ObjectOptimisticLockingFailureException.
 * Service가 retry하면 → 좌석은 이미 HELD라 status 체크에서 reject (retry storm은 결과적으로 wasted).
 */
@Entity
@Table(name = "seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = SeatStatus.AVAILABLE;
        if (this.version == null) this.version = 0L;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void hold() {
        this.status = SeatStatus.HELD;
    }

    public void confirm() {
        this.status = SeatStatus.SOLD;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
    }
}
