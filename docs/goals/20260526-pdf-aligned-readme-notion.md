# 2026-05-26 PDF 기준 README / Notion 정리

## 목표

제출용 PDF의 `Ticketing Concurrency Lab - 부하테스트 기반 티켓팅 동시성 개선` 섹션을 기준으로 GitHub README와 Notion 페이지를 정리한다.

## 기준 문서

- 제출용 PDF: `/Users/idong-u/resume/ahnlab-ai-service-portfolio/final-portfolio/lee-dongwoo-portfolio.pdf`
- PDF 생성 스크립트: `/Users/idong-u/resume/ahnlab-ai-service-portfolio/final-portfolio/build-notion-portfolio.mjs`
- GitHub repo: `/Users/idong-u/d/ticketing`
- Notion page: `https://www.notion.so/Ticketing-Concurrency-Lab-36373344235881fdb466f9b0636095df?source=copy_link`

## 작업 범위

1. `README.md`를 PDF 흐름에 맞게 재정렬한다.
   - 프로젝트 개요
   - 최종 아키텍처
   - 아키텍처 설계 기준
   - 부하테스트 및 개선 1~3
   - 상세 근거 문서
   - 구현 모듈
2. `docs/evidence/*.md`는 PDF의 `상세 테스트` 링크가 가리키는 상세 근거 문서로 유지한다.
3. Notion 페이지는 PDF와 같은 목차를 기준으로 정리하되, 상세 설명은 GitHub evidence 문서로 연결한다.

## 작성 원칙

- PDF 문장과 수치를 기준으로 한다.
- PDF보다 넓은 범위의 실험, 미완성 계획, 추정 표현은 README 하단의 상세 근거 또는 제외 범위로만 둔다.
- 사용자가 금지한 표현과 모호한 운영 추정 표현은 본문에 사용하지 않는다.
- Stage 순서는 내부 구현 설명에만 사용하고, 제출용 설명의 큰 흐름은 `부하테스트 및 개선 1~3`으로 맞춘다.

## 검증

- README 링크가 실제 파일로 연결되는지 확인한다.
- Notion 링크와 GitHub 링크가 PDF와 동일한지 확인한다.
- PDF와 README/Notion의 주요 수치가 일치하는지 확인한다.
