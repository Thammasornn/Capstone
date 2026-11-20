import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest'
import { server } from '@/mocks/node'
import { resetDb } from '@/mocks/data'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  resetDb()
  localStorage.clear()
})
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
