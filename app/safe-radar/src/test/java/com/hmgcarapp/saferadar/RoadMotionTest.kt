package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.Direction
import com.hmgcarapp.saferadar.ui.RoadMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 주행 화면 원호가 경고 문구와 같은 방향을 가리키는지(왼쪽 경고에 오른쪽 원호가 켜지면 안 된다). */
class RoadMotionTest {
    @Test fun everyDirectionExceptNearHasItsOwnArc() {
        assertEquals(Direction.entries.size - 1, RoadMotion.SECTORS.toSet().size)
        assertNull(RoadMotion.angleOf(Direction.NEAR))
    }

    @Test fun arcsPointWhereTheLabelSays() {
        Direction.entries.filter { it != Direction.NEAR }.forEach { d ->
            val a = Math.toRadians(RoadMotion.angleOf(d)!!.toDouble())
            val x = cos(a); val y = sin(a) // 화면 좌표: y 가 커지면 아래(= 뒤)
            if ("왼쪽" in d.label) assertTrue("${d.label} 은 왼쪽", x < -0.1)
            if ("오른쪽" in d.label) assertTrue("${d.label} 은 오른쪽", x > 0.1)
            if ("앞" in d.label) assertTrue("${d.label} 은 위", y < -0.1)
            if ("뒤" in d.label) assertTrue("${d.label} 은 아래", y > 0.1)
        }
    }

    @Test fun steeringCurveIsBounded() {
        assertEquals(0f, RoadMotion.curveFor(null))
        assertEquals(1f, RoadMotion.curveFor(720f))
        assertEquals(-1f, RoadMotion.curveFor(-720f))
    }
}
