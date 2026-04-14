'use client'

interface ChipGroupProps<T extends string> {
  options: { value: T; label: string; color?: string }[]
  value: T
  onChange: (v: T) => void
}

export function ChipGroup<T extends string>({ options, value, onChange }: ChipGroupProps<T>) {
  return (
    <div className="flex gap-1 flex-wrap">
      {options.map((o) => {
        const active = value === o.value
        return (
          <button
            key={o.value}
            type="button"
            onClick={() => onChange(o.value)}
            className={`text-xs px-2.5 py-1 rounded-full border transition-colors ${
              active
                ? 'text-white border-transparent'
                : 'text-gray-600 border-gray-200 bg-white hover:bg-gray-50'
            }`}
            style={active && o.color ? { backgroundColor: o.color } : undefined}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
