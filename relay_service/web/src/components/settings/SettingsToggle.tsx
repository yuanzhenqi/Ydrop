'use client'

interface SettingsToggleProps {
  label: string
  description?: string
  value: boolean
  onChange: (v: boolean) => void
}

export function SettingsToggle({ label, description, value, onChange }: SettingsToggleProps) {
  return (
    <div className="flex items-start justify-between gap-3 py-1">
      <div className="flex-1">
        <div className="text-sm text-gray-800">{label}</div>
        {description && <div className="text-xs text-gray-400 mt-0.5">{description}</div>}
      </div>
      <button
        onClick={() => onChange(!value)}
        className={`relative w-10 h-6 rounded-full transition-colors flex-shrink-0 ${
          value ? 'bg-emerald-500' : 'bg-gray-300'
        }`}
      >
        <span
          className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${
            value ? 'translate-x-4' : 'translate-x-0.5'
          }`}
        />
      </button>
    </div>
  )
}
