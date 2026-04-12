import type { Config } from 'tailwindcss'

const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        sage: { DEFAULT: '#6C8E7B', light: '#e8f5e9' },
        amber: { DEFAULT: '#D89A2B', light: '#fff8e1' },
        sky: { DEFAULT: '#4F86C6', light: '#e3f2fd' },
        rose: { DEFAULT: '#C9656C', light: '#fce4ec' },
      },
    },
  },
  plugins: [],
}
export default config
