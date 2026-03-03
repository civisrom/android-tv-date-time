#!/usr/bin/env python3
"""
Скрипт для генерации иконки приложения AndroidTVTimeFixer.
Создаёт современную иконку: TV-экран с часами, неоновое свечение,
градиентный фон в стиле glassmorphism.
"""

import math
import struct
import zlib
import os


def create_png(width, height, pixels):
    """Создаёт PNG файл из пиксельных данных"""
    def png_chunk(chunk_type, data):
        chunk_len = struct.pack('>I', len(data))
        chunk_crc = struct.pack('>I', zlib.crc32(chunk_type + data) & 0xffffffff)
        return chunk_len + chunk_type + data + chunk_crc

    signature = b'\x89PNG\r\n\x1a\n'
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    ihdr = png_chunk(b'IHDR', ihdr_data)

    raw_data = b''
    for y in range(height):
        raw_data += b'\x00'
        for x in range(width):
            raw_data += bytes(pixels[y * width + x])

    compressed = zlib.compress(raw_data, 9)
    idat = png_chunk(b'IDAT', compressed)
    iend = png_chunk(b'IEND', b'')

    return signature + ihdr + idat + iend


def create_ico(png_data, size):
    """Создаёт ICO файл из PNG данных"""
    ico_header = struct.pack('<HHH', 0, 1, 1)
    ico_entry = struct.pack('<BBBBHHII',
        size if size < 256 else 0,
        size if size < 256 else 0,
        0, 0, 1, 32,
        len(png_data),
        22
    )
    return ico_header + ico_entry + png_data


def lerp(a, b, t):
    """Линейная интерполяция"""
    return int(a + (b - a) * t)


def lerp_color(c1, c2, t):
    """Интерполяция цвета"""
    t = max(0.0, min(1.0, t))
    return (lerp(c1[0], c2[0], t), lerp(c1[1], c2[1], t),
            lerp(c1[2], c2[2], t), lerp(c1[3], c2[3], t))


def blend(base, overlay):
    """Альфа-композитинг: overlay поверх base"""
    oa = overlay[3] / 255.0
    ba = base[3] / 255.0
    out_a = oa + ba * (1 - oa)
    if out_a == 0:
        return (0, 0, 0, 0)
    r = int((overlay[0] * oa + base[0] * ba * (1 - oa)) / out_a)
    g = int((overlay[1] * oa + base[1] * ba * (1 - oa)) / out_a)
    b = int((overlay[2] * oa + base[2] * ba * (1 - oa)) / out_a)
    return (min(255, r), min(255, g), min(255, b), int(out_a * 255))


def rounded_rect(x, y, cx, cy, hw, hh, r):
    """Проверяет, находится ли точка внутри скруглённого прямоугольника.
    Возвращает расстояние до края (< 0 = внутри, > 0 = снаружи)."""
    qx = abs(x - cx) - (hw - r)
    qy = abs(y - cy) - (hh - r)
    if qx <= 0 and qy <= 0:
        return -min(-qx, -qy)
    if qx <= 0:
        return qy
    if qy <= 0:
        return qx
    return (qx * qx + qy * qy) ** 0.5


def point_on_line(px, py, x1, y1, x2, y2):
    """Расстояние от точки до отрезка"""
    line_len_sq = (x2 - x1) ** 2 + (y2 - y1) ** 2
    if line_len_sq == 0:
        return ((px - x1) ** 2 + (py - y1) ** 2) ** 0.5
    t = max(0, min(1, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / line_len_sq))
    proj_x = x1 + t * (x2 - x1)
    proj_y = y1 + t * (y2 - y1)
    return ((px - proj_x) ** 2 + (py - proj_y) ** 2) ** 0.5


def generate_icon(size=256):
    """Генерирует современную иконку с неоновым свечением и glassmorphism-эффектом"""
    pixels = []

    cx = size / 2
    cy = size / 2
    s = size / 256  # масштаб

    # ─── Палитра ──────────────────────────────────────────────
    # Глубокий градиент фона (тёмно-синий → индиго)
    bg_top = (12, 14, 32, 255)
    bg_mid = (18, 22, 52, 255)
    bg_bot = (28, 18, 58, 255)

    # Основной акцент — свежий бирюзово-зелёный (Android-стиль)
    accent = (0, 230, 160, 255)         # яркий бирюзовый
    accent_dim = (0, 160, 110, 255)     # приглушённый
    accent_glow = (0, 255, 180, 60)     # для свечения (полупрозрачный)

    # Вторичный акцент — лёгкий синий
    blue_accent = (60, 130, 255, 255)

    white = (240, 245, 255, 255)
    light_gray = (160, 175, 200, 255)
    dark_face = (18, 20, 40, 255)       # тёмный циферблат
    screen_bg = (10, 12, 24, 255)       # фон экрана ТВ

    # ─── Параметры геометрии ──────────────────────────────────
    bg_radius = 52 * s
    bg_hw = 120 * s
    bg_hh = 120 * s

    tv_hw = 80 * s
    tv_hh = 56 * s
    tv_r = 10 * s
    tv_cy = cy - 10 * s
    tv_border = 3.5 * s

    clock_r = 38 * s
    clock_cy = tv_cy

    # Ножка ТВ
    stand_top = tv_cy + tv_hh + tv_border + 2 * s
    stand_bot = stand_top + 12 * s
    stand_foot_hw = 34 * s
    stand_foot_h = 3 * s

    for y in range(size):
        for x in range(size):
            # ─── Фон (суперэллипс-стиль с градиентом) ────────────
            d_bg = rounded_rect(x, y, cx, cy, bg_hw, bg_hh, bg_radius)

            if d_bg > 1.5:
                pixels.append((0, 0, 0, 0))
                continue

            # Трёхцветный вертикальный градиент
            t_grad = y / size
            if t_grad < 0.5:
                bg_color = lerp_color(bg_top, bg_mid, t_grad * 2)
            else:
                bg_color = lerp_color(bg_mid, bg_bot, (t_grad - 0.5) * 2)

            # Радиальное виньетирование (тонкое затемнение по краям)
            dist_from_center = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            max_dist = (bg_hw ** 2 + bg_hh ** 2) ** 0.5
            vignette = 1.0 - 0.25 * (dist_from_center / max_dist) ** 1.5
            bg_color = (
                int(bg_color[0] * vignette),
                int(bg_color[1] * vignette),
                int(bg_color[2] * vignette),
                bg_color[3]
            )

            # Антиалиасинг края фона
            if d_bg > -1.5:
                alpha = max(0, min(255, int(255 * (1.5 - d_bg) / 3.0)))
                bg_color = (bg_color[0], bg_color[1], bg_color[2], alpha)

            pixel = bg_color

            # ─── Неоновое свечение вокруг ТВ ─────────────────────
            d_outer_glow = rounded_rect(x, y, cx, tv_cy,
                                        tv_hw + tv_border + 16 * s,
                                        tv_hh + tv_border + 16 * s,
                                        tv_r + tv_border + 12 * s)
            if d_outer_glow < 0:
                glow_intensity = min(1.0, (-d_outer_glow) / (14 * s))
                # Только ближняя зона к рамке
                d_outer = rounded_rect(x, y, cx, tv_cy,
                                       tv_hw + tv_border, tv_hh + tv_border,
                                       tv_r + tv_border)
                if d_outer > 0:
                    glow_falloff = max(0, 1.0 - d_outer / (16 * s))
                    glow_alpha = int(35 * glow_falloff ** 2)
                    glow_color = (accent_glow[0], accent_glow[1], accent_glow[2], glow_alpha)
                    pixel = blend(pixel, glow_color)

            # ─── Ножка ТВ ────────────────────────────────────────
            if stand_top <= y <= stand_bot:
                if abs(x - cx) < 2.5 * s:
                    t_stand = (y - stand_top) / (stand_bot - stand_top)
                    pixel = lerp_color(accent_dim, (accent_dim[0], accent_dim[1], accent_dim[2], 120), t_stand)

            # Горизонтальная подставка
            if stand_bot <= y <= stand_bot + stand_foot_h:
                if abs(x - cx) < stand_foot_hw:
                    d_foot = rounded_rect(x, y, cx, stand_bot + stand_foot_h / 2,
                                          stand_foot_hw, stand_foot_h / 2, 2 * s)
                    if d_foot < 0:
                        pixel = accent_dim

            # ─── Рамка ТВ (градиент от акцента к синему) ──────────
            d_outer = rounded_rect(x, y, cx, tv_cy,
                                   tv_hw + tv_border, tv_hh + tv_border, tv_r + tv_border)
            d_inner = rounded_rect(x, y, cx, tv_cy, tv_hw, tv_hh, tv_r)

            if d_outer < 0 and d_inner >= 0:
                t_frame = (y - (tv_cy - tv_hh - tv_border)) / (2 * (tv_hh + tv_border))
                # Градиент: бирюзовый -> синий акцент по диагонали
                t_diag = ((x - (cx - tv_hw)) / (2 * tv_hw) + t_frame) / 2
                frame_color = lerp_color(accent, blue_accent, t_diag)
                pixel = frame_color
                # Антиалиасинг внутреннего края
                if d_inner < 1.5:
                    pixel = lerp_color(screen_bg, pixel, max(0, min(1, d_inner / 1.5)))

            # ─── Экран ТВ ─────────────────────────────────────────
            if d_inner < 0:
                pixel = screen_bg

                # ─── Часы на экране ───────────────────────────────
                dx_c = x - cx
                dy_c = y - clock_cy
                dist_c = (dx_c * dx_c + dy_c * dy_c) ** 0.5

                # Свечение вокруг циферблата
                if clock_r < dist_c < clock_r + 8 * s:
                    glow_t = 1.0 - (dist_c - clock_r) / (8 * s)
                    glow_alpha = int(30 * glow_t ** 2)
                    pixel = blend(pixel, (accent[0], accent[1], accent[2], glow_alpha))

                # Внешний обод часов (двойной: тонкая яркая линия + мягкое свечение)
                if clock_r - 2.5 * s < dist_c < clock_r:
                    t_aa = (clock_r - dist_c) / (2.5 * s)
                    pixel = lerp_color(screen_bg, accent, max(0, min(1, t_aa)))

                # Циферблат
                elif dist_c < clock_r - 2.5 * s:
                    # Тонкий радиальный градиент внутри циферблата
                    t_face = dist_c / (clock_r - 2.5 * s)
                    face_inner = (22, 24, 48, 255)
                    pixel = lerp_color(face_inner, dark_face, t_face ** 0.7)

                    # Часовые метки
                    is_mark = False
                    for hour in range(12):
                        angle = math.radians(hour * 30 - 90)
                        if hour % 3 == 0:
                            mark_inner = clock_r - 13 * s
                            mark_outer = clock_r - 5 * s
                            mark_thick = 2.2 * s
                            mark_color = white
                        else:
                            mark_inner = clock_r - 9 * s
                            mark_outer = clock_r - 5 * s
                            mark_thick = 1.2 * s
                            mark_color = light_gray

                        mx1 = cx + mark_inner * math.cos(angle)
                        my1 = clock_cy + mark_inner * math.sin(angle)
                        mx2 = cx + mark_outer * math.cos(angle)
                        my2 = clock_cy + mark_outer * math.sin(angle)

                        d_mark = point_on_line(x, y, mx1, my1, mx2, my2)
                        if d_mark < mark_thick:
                            is_mark = True
                            t_aa = d_mark / mark_thick
                            pixel = lerp_color(mark_color, pixel, t_aa ** 0.8)
                            break

                    if not is_mark:
                        # Часовая стрелка (10:10)
                        hour_angle = math.radians(10 * 30 - 90 + 5)
                        hour_len = (clock_r - 13 * s) * 0.52
                        hx = cx + hour_len * math.cos(hour_angle)
                        hy = clock_cy + hour_len * math.sin(hour_angle)
                        d_hour = point_on_line(x, y, cx, clock_cy, hx, hy)

                        # Минутная стрелка
                        min_angle = math.radians(2 * 30 - 90)
                        min_len = (clock_r - 13 * s) * 0.78
                        mx = cx + min_len * math.cos(min_angle)
                        my = clock_cy + min_len * math.sin(min_angle)
                        d_min = point_on_line(x, y, cx, clock_cy, mx, my)

                        if d_hour < 3.2 * s:
                            t_aa = d_hour / (3.2 * s)
                            pixel = lerp_color(white, pixel, t_aa ** 0.7)
                        elif d_min < 1.8 * s:
                            t_aa = d_min / (1.8 * s)
                            pixel = lerp_color(accent, pixel, t_aa ** 0.7)
                        elif dist_c < 3.5 * s:
                            # Центральная точка с акцентным цветом
                            t_aa = dist_c / (3.5 * s)
                            pixel = lerp_color(accent, pixel, t_aa ** 0.6)

            pixels.append(pixel)

    return pixels


def main():
    """Основная функция для генерации иконок"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)

    size = 256
    print(f"Generating {size}x{size} icon...")
    pixels = generate_icon(size)

    png_data = create_png(size, size, pixels)

    png_path = os.path.join(project_root, 'icon.png')
    with open(png_path, 'wb') as f:
        f.write(png_data)
    print(f"Created: {png_path}")

    ico_data = create_ico(png_data, size)
    ico_path = os.path.join(project_root, 'icon.ico')
    with open(ico_path, 'wb') as f:
        f.write(ico_data)
    print(f"Created: {ico_path}")

    print("Icon generation complete!")


if __name__ == '__main__':
    main()
