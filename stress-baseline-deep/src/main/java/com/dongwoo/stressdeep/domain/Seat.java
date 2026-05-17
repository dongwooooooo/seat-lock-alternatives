package com.dongwoo.stressdeep.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Deep stress 시나리오 — 기본 Seat 엔티티 (version 없음, 비관적 락만 의존).
 */
@Entity
@Table(name = "seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

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
