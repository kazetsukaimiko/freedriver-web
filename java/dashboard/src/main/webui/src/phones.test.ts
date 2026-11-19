import { expect, it } from 'vitest'
import { canonicalPhone } from './phones'

/** The eight shapes UsPhoneNumbersTest holds for 5555550123 and for 1234567890. */
const SHAPES: Array<[string, string]> = [
  ['5555550123', '+15555550123'],
  ['555-555-0123', '+15555550123'],
  ['(555) 555-0123', '+15555550123'],
  ['555.555.0123', '+15555550123'],
  ['555 555 0123', '+15555550123'],
  ['1-555-555-0123', '+15555550123'],
  ['+1 555 555 0123', '+15555550123'],
  ['+15555550123', '+15555550123'],
  ['1234567890', '+11234567890'],
  ['123-456-7890', '+11234567890'],
  ['(123) 456-7890', '+11234567890'],
  ['123.456.7890', '+11234567890'],
  ['123 456 7890', '+11234567890'],
  ['1-123-456-7890', '+11234567890'],
  ['+1 123 456 7890', '+11234567890'],
  ['+11234567890', '+11234567890'],
]

it('maps the eight typed shapes to +1 and ten digits', () => {
  for (const [raw, canonical] of SHAPES) {
    expect(canonicalPhone(raw), raw).toBe(canonical)
  }
})

it('returns null for eleven digits and for a shape inside a longer string', () => {
  expect(canonicalPhone('55555501234')).toBeNull()
  expect(canonicalPhone('call 5555550123')).toBeNull()
})
