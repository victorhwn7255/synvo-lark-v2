interface StatusChipProps {
  label: string
  tone: 'neutral' | 'working' | 'positive' | 'warning' | 'negative'
}

export function StatusChip({ label, tone }: StatusChipProps) {
  return (
    <span className={`status-chip status-chip--${tone}`}>
      <span aria-hidden="true" className="status-chip__dot" />
      {label}
    </span>
  )
}
