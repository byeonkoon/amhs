import React from 'react';
import { ApiError } from '../api';

/**
 * The four states every screen must be able to show. Keeping them here means a screen
 * cannot invent its own wording for "nothing yet" or "it broke".
 */
export function Loading({ what }: { what: string }) {
  return (
    <div className="state" role="status" aria-live="polite">
      <div className="spin" />
      <b>{what}을(를) 불러오는 중</b>
      <p>서버 응답을 기다리고 있습니다.</p>
    </div>
  );
}

/** Callers pass a finished sentence so the wording reads naturally in Korean. */
export function Empty({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="state">
      <b>{title}</b>
      <p>{hint ?? '표시할 데이터가 아직 없습니다. 설정이나 조회 조건을 확인해 주세요.'}</p>
    </div>
  );
}

export function Failed({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const api = error instanceof ApiError ? error : null;
  const status = api?.status ?? 0;
  const message = error instanceof Error ? error.message : '알 수 없는 오류입니다.';
  return (
    <div className="state err" role="alert">
      <b>{title(status)}</b>
      <p>{message}</p>
      <p className="mono" style={{ fontSize: 11 }}>{status ? `HTTP ${status}` : 'NETWORK'} · {api?.url ?? ''}</p>
      <p>{advice(status)}</p>
      <button type="button" onClick={onRetry}>다시 시도</button>
    </div>
  );
}

function title(status: number): string {
  if (status === 404) return '자원을 찾지 못했습니다';
  if (status === 401 || status === 403) return '권한이 없습니다';
  if (status === 503) return '서버가 준비되지 않았습니다';
  if (status >= 500) return '서버 오류가 발생했습니다';
  if (status === 0) return '서버에 연결하지 못했습니다';
  return '요청을 처리하지 못했습니다';
}

function advice(status: number): string {
  if (status === 404) return '지도 revision이나 사이트 번호가 올바른지 확인해 주세요.';
  if (status === 401 || status === 403) return '조회 권한이 있는 계정으로 접속했는지 확인해 주세요.';
  if (status === 503 || status === 0) return '서버와 데이터베이스가 기동되었는지 확인해 주세요.';
  return '잠시 후 다시 시도하고, 반복되면 서버 로그를 확인해 주세요.';
}

/**
 * Position freshness. Configuration positions are not live reports, and a live report
 * that stopped arriving is stale rather than current. The screen must say which it is.
 */
export type Freshness = { kind: 'configured' | 'live' | 'stale' | 'none'; label: string; chip: string };

export function freshnessOf(positionSource: string, lastReceivedAt?: string | null, staleAfterMs = 10_000): Freshness {
  if (positionSource === 'INITIAL_CONFIGURATION') return { kind: 'configured', label: '설정 위치', chip: 'id' };
  if (!lastReceivedAt) return { kind: 'none', label: '수신 없음', chip: 'wr' };
  const age = Date.now() - new Date(lastReceivedAt).getTime();
  return age > staleAfterMs
    ? { kind: 'stale', label: `수신 지연 ${Math.round(age / 1000)}초`, chip: 'wr' }
    : { kind: 'live', label: '실시간 수신', chip: 'ok' };
}

export function Chip({ tone, title, children }: { tone: string; title?: string; children: React.ReactNode }) {
  return <span className={`chip ${tone}`} title={title}>{children}</span>;
}
