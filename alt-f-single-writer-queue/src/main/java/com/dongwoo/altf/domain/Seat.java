package com.dongwoo.altf.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대안 F — 단일 작성자 큐.
 *
 * 락도 UNIQUE도 없다. 정확성은 worker 스레드 1개가 큐를 직렬로 비우는 데서 나온다.
 * DB 입장에선 동시 INSERT/UPDATE가 한 건도 들어오지 않는다 (race 자체가 사라짐).
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
