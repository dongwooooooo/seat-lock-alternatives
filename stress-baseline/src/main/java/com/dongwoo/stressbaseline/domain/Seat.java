package com.dongwoo.stressbaseline.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stress baseline 시나리오에서는 부수 컬럼을 제거하고 id + status만 유지한다.
 * 측정 대상이 락 자체가 아니라 풀/대기/처리량이므로 스키마 단순화.
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
