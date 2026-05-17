package com.dongwoo.alte.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대안 E — SELECT FOR UPDATE SKIP LOCKED.
 *
 * version 컬럼 없음. row-level lock 자체가 동시성 차단 메커니즘.
 * SKIP LOCKED는 다른 트랜잭션이 락 잡은 행을 "건너뛰고" 빈 결과를 반환한다.
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
