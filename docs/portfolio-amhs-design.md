# AMHS Integration Platform Design Overview

## Purpose

This note describes the design intent and current implementation boundary of a personal AMHS integration platform. It contains no customer, employer, plant, legacy project, production address, or equipment-specific identifier. The diagrams are conceptual views of the platform and are not a claim that every shown interface is implemented.

## System boundary

The web client uses versioned HTTP APIs for configuration and operation views. A Spring Boot service owns the transport-order lifecycle, fleet state, map and route decisions, traffic reservations, and the in-process simulation. PostgreSQL stores operational records and configuration. Vehicle and equipment integration sit behind adapters so the core can reason in terms of stable domain commands and reported outcomes.

In simulation mode, the server executes vehicle movement and equipment handover against virtual state. In operation mode, virtual state is not treated as a report from real equipment. The real-device adapter and its inbound acknowledgement path remain implementation work.

![Conceptual AMHS component boundaries](portfolio-amhs-architecture.svg)

## Design decisions

- A transport request has one explicit lifecycle. Acceptance, assignment, movement, handover evidence, recovery, and terminal status remain distinguishable.
- A vehicle receives only the route portion it is authorized to occupy. Traffic reservations represent permission to use shared track resources and are released as the vehicle advances or work is resolved.
- Vehicle type and network are part of eligibility and route choice. OHT rail movement and AGV floor movement share transport concepts while retaining separate topology constraints.
- Simulation exercises the same order, vehicle-eligibility, routing, and traffic decisions as operation. Simulated equipment state and simulated handover evidence stand in for sensors only in simulation mode.
- Service exclusion prevents new transport work assignment. It does not by itself prohibit home, charging, yielding, or other movement policies. OHT maintenance-zone behavior and AGV home-return behavior remain distinct policies.
- A recovery record describes an operator's confirmed carrier location. It does not physically move a carrier or silently rewrite a simulated vehicle's load state.

## Verification intent

The platform is developed around reproducible end-to-end scenarios: accept a request, validate eligibility, route and reserve resources, execute a movement, perform a virtual equipment handover, and either complete or enter a recoverable state. Fault cases include unavailable devices, stale status, route conflict, cancellation while movement is outstanding, and uncertain carrier location.

![Conceptual end-to-end verification sequence](portfolio-amhs-verification.svg)

The repository records completed implementation and tests separately from design decisions and planned adapter work. No real-device, factory, or safety-certification result is implied by the simulator.

---------------------------------------------------------------------------------------------------------------------------------------------------
# AMHS 통합 플랫폼 설계 개요
## 목적

본 문서는 개인 AMHS 통합 플랫폼의 설계 의도와 현재 구현 범위를 설명합니다. 고객사, 재직 회사, 공장, 기존 프로젝트, 운영 주소, 특정 설비를 식별할 수 있는 정보는 포함하지 않습니다. 문서의 다이어그램은 플랫폼의 개념적 구조를 표현한 것이며, 표시된 모든 인터페이스가 현재 구현되어 있음을 의미하지 않습니다.

## 시스템 경계

웹 클라이언트는 버전이 명시된 HTTP API를 통해 설정 정보와 운영 상태를 조회합니다. Spring Boot 서비스는 반송 작업의 수명주기, Fleet 상태, Map 및 Route 판단, Traffic Reservation, 내부 Simulation을 담당합니다. PostgreSQL은 운영 이력과 설정 정보를 저장합니다. 차량 및 설비 연동은 Adapter 계층 뒤에 위치하며, Core는 개별 장비의 통신 방식이 아니라 안정적인 Domain Command와 결과 상태를 기준으로 동작하도록 구성했습니다.

Simulation 모드에서는 서버가 가상 상태를 기반으로 차량 이동과 설비 인수도를 수행합니다. Operation 모드에서는 가상 상태를 실제 설비의 상태 보고로 간주하지 않습니다. 실제 장비 Adapter와 장비로부터의 상태·완료 응답을 수신하는 경로는 현재 후속 구현 범위입니다.

## 주요 설계 판단
하나의 반송 요청은 명확한 수명주기를 갖도록 구성했습니다. 작업 접수, 차량 배정, 이동, 인수도 완료 근거, 복구 상태, 최종 종료 상태를 서로 구분하여 관리합니다.
차량에는 실제 점유가 허가된 경로 구간만 전달합니다. Traffic Reservation은 공용 주행 자원에 대한 사용 권한을 의미하며, 차량이 이동하거나 작업이 해소되는 과정에 따라 점유 자원을 순차적으로 해제합니다.
차량 종류와 이동 네트워크는 차량 적합성 판단과 경로 선택 조건에 포함됩니다. OHT의 천장 레일과 AGV의 바닥 주행로는 동일한 반송 개념을 공유하지만, 각각의 Topology 제약은 별도로 유지합니다.
Simulation은 실제 운용과 동일한 작업 처리, 차량 적합성 판단, Routing, Traffic 판단 로직을 사용합니다. 단, 가상 설비 상태와 가상 인수도 완료 정보는 Simulation 모드에서만 실제 센서 신호를 대신합니다.
Service Exclusion 상태는 새로운 반송 작업의 배정을 제한합니다. 다만 Home 이동, Charging, Yielding 등 모든 차량 이동 정책을 일괄적으로 금지하지는 않습니다. OHT의 유지보수 구역 처리와 AGV의 Home 복귀 정책은 서로 별도의 정책으로 관리합니다.
Recovery 정보는 운영자가 확인한 실제 화물 위치를 기록하는 용도로 사용합니다. Recovery 입력만으로 화물을 물리적으로 이동시키거나 Simulation 차량의 적재 상태를 임의로 변경하지 않습니다.
검증 방향

플랫폼은 재현 가능한 End-to-End 시나리오를 중심으로 개발하고 있습니다.

반송 요청 접수 → 차량 적합성 검증 → 경로 계산 및 자원 점유 → 차량 이동 → 가상 설비 인수도 → 작업 완료 또는 복구 상태 진입

과정을 하나의 검증 흐름으로 구성합니다.

## 비정상 시나리오에는 다음과 같은 경우를 포함합니다.

차량 또는 설비 사용 불가
오래된 상태 정보
경로 및 자원 점유 충돌
차량 이동 중 작업 취소
화물 위치가 불확실한 상태

저장소에서는 현재 구현이 완료된 기능과 테스트 결과, 설계 결정, 향후 Adapter 구현 계획을 구분하여 관리합니다. Simulator의 결과가 실제 장비, 공장 운영 환경 또는 산업 안전 인증 수준의 검증 결과를 의미하지는 않습니다.