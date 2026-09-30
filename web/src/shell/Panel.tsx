import React from 'react';

/**
 * A section that folds. Monitoring screens carry more than fits at once, and the answer is to
 * let the operator choose what is open rather than to leave things out.
 *
 * <p>Open state is remembered per id for the session, so a screen does not reset what someone
 * arranged every time they navigate away and back.
 */
const openState = new Map<string, boolean>();

export function Panel(props: {
  id: string; title: string; aux?: React.ReactNode; defaultOpen?: boolean;
  padded?: boolean; children: React.ReactNode;
}) {
  const [open, setOpen] = React.useState(() => openState.get(props.id) ?? props.defaultOpen ?? false);
  return (
    <details className="panel" open={open}
      onToggle={e => { const v = (e.currentTarget as HTMLDetailsElement).open; openState.set(props.id, v); setOpen(v); }}>
      <summary>
        {props.title}
        {props.aux !== undefined && <span className="aux">{props.aux}</span>}
      </summary>
      <div className={props.padded ? 'pb pad' : 'pb'}>{props.children}</div>
    </details>
  );
}

/** A dense grid. `cols` is a CSS grid-template-columns value, set by the caller per table. */
export function Grid(props: { cols: string; head: React.ReactNode[]; children: React.ReactNode }) {
  return (
    <div className="sgrid" style={{ ['--cols' as string]: props.cols }}>
      <div className="hd">{props.head.map((h, i) => <span key={i}>{h}</span>)}</div>
      {props.children}
    </div>
  );
}

export function Row(props: { selected?: boolean; onClick?: () => void; children: React.ReactNode }) {
  const className = `rw${props.selected ? ' sel' : ''}`;
  return props.onClick
    ? <button type="button" className={className} onClick={props.onClick}>{props.children}</button>
    : <div className={className}>{props.children}</div>;
}
