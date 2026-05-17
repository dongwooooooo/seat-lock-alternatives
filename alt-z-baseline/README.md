# 대안 Z — 비관적 락 + partial UNIQUE (채택)

본 디렉터리는 placeholder. 실제 구현 + 측정은 [`stress-baseline/`](../stress-baseline/) 및 [`stress-baseline-deep/`](../stress-baseline-deep/) 에 있다.

- `stress-baseline/` — race 정합성 + 부하 시나리오 3종 (Hot seat / Distributed / Pool exhaustion)
- `stress-baseline-deep/` — 추가 실패 모드 6종 (JPA cache / Deadlock / Lock timeout / Starvation / Rollback storm / Connection leak)

채택 사유: 6대안 (A~F) 모두 race 정합성은 달성하지만 각자 단점 있음. 비관적 락 + partial UNIQUE 2단 방어가 단일 노드에선 가장 안전 + 단순. 부하 한계는 Stage 3 (대기열) 로 해결 — 자세한 진입 논리는 메인 [README.md](../README.md#stage-3-진입-논리) 참조.
