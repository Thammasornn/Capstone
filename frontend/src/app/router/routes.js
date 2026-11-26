/**
 * ตาราง route ตาม frontend-structure.md ข้อ 3 (navigation ใน Figma)
 *
 * meta.roles   role ที่เข้าได้ (route ลูกสืบทอดจาก parent; route ลูกที่ระบุเองจะใช้ของตัวเอง)
 * meta.public  ไม่ต้องล็อกอิน
 * meta.guestOnly  ล็อกอินแล้วเข้าไม่ได้ (เด้งไปหน้าแรกของ role)
 * meta.title   ชื่อแท็บเบราว์เซอร์
 *
 * ⚠️ การซ่อนเมนู/route เป็นแค่ UX — Backend ตรวจสิทธิ์จริงทุก request
 */
import AuthLayout from '../layouts/AuthLayout.vue'
import StudentLayout from '../layouts/StudentLayout.vue'
import StaffLayout from '../layouts/StaffLayout.vue'
import CourseHubLayout from '../layouts/CourseHubLayout.vue'

const STUDENT = ['student']
const STAFF = ['lecturer', 'admin']
/**
 * Add/Edit Course: รอคำตอบ Q6 (อาจารย์แก้วิชาตัวเองได้ไหม) — ตอนนี้เปิดทั้งสอง role
 * ถ้าตกลงว่าเฉพาะ admin ให้เปลี่ยนเป็น ['admin'] บรรทัดเดียว
 */
const COURSE_EDITORS = ['admin', 'lecturer']

/** @type {import('vue-router').RouteRecordRaw[]} */
export const routes = [
  // '/' → login แล้ว guard จะพาคนที่ล็อกอินแล้วไปหน้าแรกของ role
  { path: '/', name: 'root', redirect: { name: 'login' } },

  {
    path: '/login',
    component: AuthLayout,
    meta: { public: true, guestOnly: true },
    children: [
      {
        path: '',
        name: 'login',
        component: () => import('@/features/auth/pages/LoginPage.vue'),
        meta: { title: 'เข้าสู่ระบบ' },
      },
    ],
  },

  {
    path: '/student',
    component: StudentLayout,
    meta: { roles: STUDENT },
    children: [
      {
        path: '',
        name: 'student-home',
        component: () => import('@/features/my-courses/pages/StudentHomePage.vue'),
        meta: { title: 'หน้าแรก' },
      },
      {
        path: 'courses',
        name: 'student-courses',
        component: () => import('@/features/my-courses/pages/MyCoursesPage.vue'),
        meta: { title: 'วิชาของฉัน' },
      },
      {
        path: 'attendance',
        name: 'student-attendance',
        component: () => import('@/features/my-attendance/pages/MyAttendancePage.vue'),
        meta: { title: 'ประวัติการเข้าเรียน' },
      },
      {
        path: 'face',
        name: 'face-registration',
        component: () => import('@/features/face-registration/pages/FaceRegistrationPage.vue'),
        meta: { title: 'ลงทะเบียนใบหน้า' },
      },
      {
        path: 'face/manage',
        name: 'face-manage',
        component: () =>
          import('@/features/face-registration/pages/ManageFaceRegistrationPage.vue'),
        meta: { title: 'จัดการข้อมูลใบหน้า' },
      },
    ],
  },

  {
    path: '/',
    component: StaffLayout,
    meta: { roles: STAFF },
    children: [
      {
        path: 'classrooms',
        name: 'room-selection',
        component: () => import('@/features/room-monitor/pages/RoomSelectionPage.vue'),
        meta: { title: 'เลือกห้องเรียน' },
      },
      {
        path: 'classrooms/:roomId',
        name: 'room-live',
        component: () => import('@/features/room-monitor/pages/RoomLivePage.vue'),
        props: true,
        meta: { title: 'Dashboard ห้องเรียน' },
      },
      {
        path: 'courses/new',
        name: 'course-new',
        component: () => import('@/features/course-management/pages/CourseFormPage.vue'),
        meta: { title: 'เพิ่มรายวิชา', roles: COURSE_EDITORS },
      },
      {
        path: 'courses/:courseId/edit',
        name: 'course-edit',
        component: () => import('@/features/course-management/pages/CourseFormPage.vue'),
        props: true,
        meta: { title: 'แก้ไขรายวิชา', roles: COURSE_EDITORS },
      },
      {
        path: 'courses/:courseId/sections/:sectionId/sessions',
        name: 'section-sessions',
        component: () => import('@/features/course-management/pages/SectionSessionsPage.vue'),
        props: true,
        meta: { title: 'คาบเรียน', roles: COURSE_EDITORS },
      },
      {
        path: 'courses/:courseId/sections/:sectionId/students',
        name: 'section-students',
        component: () => import('@/features/section-roster/pages/SectionRosterPage.vue'),
        props: true,
        meta: { title: 'รายชื่อนักศึกษา', roles: COURSE_EDITORS },
      },
      {
        path: 'courses',
        component: CourseHubLayout,
        children: [
          { path: '', name: 'courses', redirect: { name: 'courses-manage' } },
          {
            path: 'manage',
            name: 'courses-manage',
            component: () => import('@/features/course-management/pages/ManageCoursesPage.vue'),
            meta: { title: 'จัดการรายวิชา' },
          },
          {
            path: 'registration',
            name: 'courses-registration',
            component: () =>
              import('@/features/registration-status/pages/RegistrationStatusPage.vue'),
            meta: { title: 'สถานะลงทะเบียนใบหน้า' },
          },
          {
            path: 'analytics',
            name: 'courses-analytics',
            component: () => import('@/features/attendance-analytics/pages/AnalyticsPage.vue'),
            meta: { title: 'วิเคราะห์การเข้าเรียน' },
          },
          {
            path: 'at-risk',
            name: 'courses-at-risk',
            component: () => import('@/features/attendance-analytics/pages/AtRiskStudentsPage.vue'),
            meta: { title: 'นักศึกษากลุ่มเสี่ยง' },
          },
        ],
      },
      {
        path: 'reports/sessions/:sessionId',
        name: 'session-report',
        component: () => import('@/features/session-report/pages/SessionReportPage.vue'),
        props: true,
        meta: { title: 'รายงานรายคาบ' },
      },
    ],
  },

  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/shared/pages/ForbiddenPage.vue'),
    meta: { public: true, title: 'ไม่มีสิทธิ์เข้าถึง' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/shared/pages/NotFoundPage.vue'),
    meta: { public: true, title: 'ไม่พบหน้า' },
  },
]
