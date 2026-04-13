'use client'

import type { ReactNode } from 'react'

interface SettingsFieldProps {
  label: string
  hint?: string
  children: ReactNode
}

export function SettingsField({ label, hint, children }: SettingsFieldProps) {
  return (
    <div className="space-y-1">
      <label className="text-xs font-medium text-gray-600">{label}</label>
      {children}
      {hint && <p className="text-xs text-gray-400">{hint}</p>}
    </div>
  )
}

interface TextInputProps {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: 'text' | 'password' | 'url'
  disabled?: boolean
}

export function TextInput({ value, onChange, placeholder, type = 'text', disabled }: TextInputProps) {
  return (
    <input
      type={type}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      disabled={disabled}
      className="w-full text-sm border rounded-lg px-3 py-2 outline-none focus:border-emerald-400 disabled:bg-gray-50 disabled:text-gray-400"
    />
  )
}

interface NumberSelectProps {
  value: number
  onChange: (v: number) => void
  options: { value: number; label: string }[]
}

export function NumberSelect({ value, onChange, options }: NumberSelectProps) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(Number(e.target.value))}
      className="w-full text-sm border rounded-lg px-3 py-2 bg-white outline-none focus:border-emerald-400"
    >
      {options.map((o) => (
        <option key={o.value} value={o.value}>{o.label}</option>
      ))}
    </select>
  )
}
