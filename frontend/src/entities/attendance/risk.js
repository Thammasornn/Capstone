/**
 * เกณฑ์นักศึกษากลุ่มเสี่ยง (ทีมตัดสินแล้ว — frontend-structure.md ข้อ 4)
 * นับจากจำนวนครั้งที่ขาด (`absent`) รวมทุกคาบของนักศึกษาในวิชานั้น จาก API 5.6
 *   Low    ขาด 0–1 ครั้ง
 *   Medium ขาด 2 ครั้ง
 *   High   ขาด 3 ครั้งขึ้นไป
 * แก้เกณฑ์ที่นี่ที่เดียว ตาราง, แท็บ At-Risk และ Dashboard ใช้ตัวเดียวกัน
 * ⚠️ ห้ามแสดงระดับความเสี่ยงให้ role `student` — ใช้ canSeeRisk() ก่อนแสดง
 *
 * @typedef {'low'|'medium'|'high'} RiskLevel
 */

export const RISK_THRESHOLDS = Object.freeze({ medium: 2, high: 3 })

/**
 * @param {number | null | undefined} absent จำนวนครั้งที่ขาด
 * @returns {RiskLevel | null} null เมื่อไม่มีข้อมูล
 */
export function getRiskLevel(absent) {
  if (typeof absent !== 'number' || !Number.isFinite(absent) || absent < 0) return null
  if (absent >= RISK_THRESHOLDS.high) return 'high'
  if (absent >= RISK_THRESHOLDS.medium) return 'medium'
  return 'low'
}

/** @type {Readonly<Record<RiskLevel, { label: string, labelEn: string, tone: import('./status').Tone, rank: number }>>} */
export const RISK_LEVELS = Object.freeze({
  high: { label: 'เสี่ยงสูง', labelEn: 'High', tone: 'danger', rank: 3 },
  medium: { label: 'เสี่ยงปานกลาง', labelEn: 'Medium', tone: 'warning', rank: 2 },
  low: { label: 'เสี่ยงต่ำ', labelEn: 'Low', tone: 'success', rank: 1 },
})

/** @param {RiskLevel | null} level */
export function getRiskMeta(level) {
  return level ? RISK_LEVELS[level] : { label: '-', labelEn: '-', tone: 'neutral', rank: 0 }
}

/** แท็บ At-Risk แสดงเฉพาะ Medium/High @param {RiskLevel | null} level */
export function isAtRisk(level) {
  return level === 'medium' || level === 'high'
}

/** @param {import('@/shared/constants/roles').Role | null | undefined} role */
export function canSeeRisk(role) {
  return role === 'lecturer' || role === 'admin'
}

/**
 * แถวนักศึกษาจาก API 5.6 → เพิ่ม riskLevel แล้วเรียงเสี่ยงมาก → น้อย (ขาดมากก่อน, รหัสนักศึกษา)
 * @template {{ absent: number, studentCode: string }} T
 * @param {T[]} students
 * @returns {(T & { riskLevel: RiskLevel | null })[]}
 */
export function withRiskLevel(students) {
  return students
    .map((s) => ({ ...s, riskLevel: getRiskLevel(s.absent) }))
    .sort(
      (a, b) =>
        getRiskMeta(b.riskLevel).rank - getRiskMeta(a.riskLevel).rank ||
        b.absent - a.absent ||
        a.studentCode.localeCompare(b.studentCode),
    )
}
