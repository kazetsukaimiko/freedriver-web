/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { canonicalPhone } from './phones'

// Vitest runs from java/dashboard/src/main/webui, locally and in CI.
const fixturePath = `${process.cwd()}/../../../../../sms-model/src/test/resources/us-phone-shapes.json`

type Pair = { raw: string; stored: string }
type Fixture = { dashboard: Pair[]; router: Pair[]; nonMatches: string[] }

const fixture = JSON.parse(readFileSync(fixturePath, 'utf8')) as Fixture

it('maps each shared example to the stored form', () => {
  expect(fixture.dashboard, 'dashboard').toHaveLength(8)
  expect(fixture.router, 'router').toHaveLength(8)
  expect(fixture.nonMatches, 'nonMatches').toHaveLength(2)
  for (const group of [fixture.dashboard, fixture.router]) {
    for (const { raw, stored } of group) {
      expect(canonicalPhone(raw), raw).toBe(stored)
    }
  }
  for (const raw of fixture.nonMatches) {
    expect(canonicalPhone(raw), raw).toBeNull()
  }
})
