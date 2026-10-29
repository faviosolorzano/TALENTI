import { apiFetch } from './client'
import type { DoraAvailability } from './realtime'
export interface PracticeOptions {
  bases: {
    basis: string
    available: boolean
    vacancies: {
      id: string
      title: string
      company: string
      topics: { slug: string; name: string }[]
    }[]
  }[]
  topics: { slug: string; name: string }[]
  maxQuestions: number
  quota: { used: number; limit: number; resetsAt: string }
  voiceAvailable: boolean
  interviewNotice: { version: string; text: string }
  /** Provider and real-time availability (012-38). Missing on an older backend. */
  dora?: DoraAvailability
}
export interface DoraFeedback {
  status: string
  criteria: { criterion: string; name: string; level: number; comment: string; quotes: string[] }[]
  total: number | null
  generatedBy: string
  retryable: boolean
}
export interface DoraAnswer {
  turnId: string
  attempt: number
  inputMode: string
  transcriptRaw: string
  transcriptFinal: string
  corrected: boolean
  feedback: DoraFeedback | null
}
export interface DoraSession {
  versions?: { llmAdapter: string }
  id: string
  mode: string
  basis: string
  topic: string | null
  questionCount: number | null
  voice: boolean
  status: string
  /** `TURN_BASED` (default) or `REALTIME` (012-39, 012-53). */
  channel?: string
  nextPosition: number | null
  answered: number
  degraded: string[]
  createdAt: string
  questions: {
    position: number
    text: string
    source: string
    generatedBy: string
    answer: DoraAnswer | null
  }[]
}
export interface DoraSessionPage {
  items: {
    id: string
    createdAt: string
    basis: string
    topic: string | null
    status: string
    questionCount: number
    answered: number
  }[]
  page: number
  size: number
  total: number
  totalPages: number
}
export interface ExamQuestion {
  id: string
  type: 'CHOICE' | 'OPEN' | 'CODE'
  prompt: string
  generatedBy: string
  options: { id: string; text: string }[]
  rubric: { id: string; description: string; levels: Record<string, string> }[]
  language: string | null
  kind: string | null
  initialCode: string | null
  cases: {
    id: string
    input: string | null
    expectedOutput: string | null
    schema: string | null
    tables: SqlFixture[]
    expectedTable: SqlTable | null
  }[]
}
export interface SqlTable {
  columns: string[]
  rows: (string | null)[][]
  types?: string[]
}
export interface SqlFixture {
  name: string
  columns: { name: string; type: string }[]
  rows: (string | null)[][]
}
export interface ExamAnswer {
  questionId: string
  choiceId: string | null
  text: string | null
  code: string | null
  revision: number
  runCount: number
  updatedAt: string
}
export interface ExamAttempt {
  id: string
  applicationId: string
  status: string
  gradingStatus: string
  noticeVersion: string
  notice: string
  startedAt: string | null
  deadlineAt: string | null
  remainingSeconds: number
  questions: ExamQuestion[]
  answers: ExamAnswer[]
  events: { kind: string; occurredAt: string }[]
}
export interface ExamPreparation {
  status: string
  retryAfter: number
}
export interface ExamRun {
  cases: {
    passed: boolean
    output: string | null
    error: string | null
    ms: number
    table: SqlTable | null
  }[]
}
export interface GithubLink {
  status: string
  login: string
  githubUserId: number
  verifiedAt: string
}
export interface GithubRepo {
  id: string
  name: string
  description: string | null
  url: string
  primaryLanguage: string | null
  languages: { name: string; percent: number }[]
  topics: string[]
  archived: boolean
  pushedAt: string
  selected: boolean
}
export interface GithubSuggestion {
  slug: string
  name: string
  evidence: { repo: string; source: string; share?: number }[]
  currentLevel: number | null
  currentEvidence: string | null
}
export const workflowApi = apiFetch

/** Decode incrementally; one network write per turn, including its idempotency key. */
export async function streamDora(
  path: string,
  options: { json?: unknown; form?: FormData; signal: AbortSignal },
  onEvent: (name: string, data: Record<string, unknown>) => void,
) {
  const response = await apiFetch<Response>(path, { method: 'POST', rawResponse: true, ...options })
  if (!response.body) throw new Error('missing-stream')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const process = (block: string) => {
    const lines = block.split('\n')
    const event =
      lines
        .find((line) => line.startsWith('event:'))
        ?.slice(6)
        .trim() ?? 'message'
    const payload = lines
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    if (payload) onEvent(event, JSON.parse(payload) as Record<string, unknown>)
  }
  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      buffer = buffer.replace(/\r\n/g, '\n')
      let end: number
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        process(buffer.slice(0, end))
        buffer = buffer.slice(end + 2)
      }
      if (done) {
        if (buffer.trim()) process(buffer)
        break
      }
    }
  } catch (error) {
    await reader.cancel().catch(() => {})
    throw error
  } finally {
    reader.releaseLock()
  }
}
