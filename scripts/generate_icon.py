#!/usr/bin/env python3
"""
Скрипт для генерации иконки приложения AndroidTVTimeFixer.
Создаёт иконку с изображением часов и Android TV.
"""

import struct
import zlib
import os

def create_png(width, height, pixels):
    """Создаёт PNG файл из пиксельных данных"""
    def png_chunk(chunk_type, data):
        chunk_len = struct.pack('>I', len(data))
        chunk_crc = struct.pack('>I', zlib.crc32(chunk_type + data) & 0xffffffff)
        return chunk_len + chunk_type + data + chunk_crc

    # PNG signature
    signature = b'\x89PNG\r\n\x1a\n'

    # IHDR chunk
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    ihdr = png_chunk(b'IHDR', ihdr_data)

    # IDAT chunk (image data)
    raw_data = b''
    for y in range(height):
        raw_data += b'\x00'  # filter byte
        for x in range(width):
            raw_data += bytes(pixels[y * width + x])

    compressed = zlib.compress(raw_data, 9)
    idat = png_chunk(b'IDAT', compressed)

    # IEND chunk
    iend = png_chunk(b'IEND', b'')

    return signature + ihdr + idat + iend


def create_ico(png_data, size):
    """Создаёт ICO файл из PNG данных"""
    # ICO header
    ico_header = struct.pack('<HHH', 0, 1, 1)  # Reserved, Type (1=icon), Count

    # ICO directory entry
    ico_entry = struct.pack('<BBBBHHII',
        size if size < 256 else 0,  # Width
        size if size < 256 else 0,  # Height
        0,  # Color palette
        0,  # Reserved
        1,  # Color planes
        32,  # Bits per pixel
        len(png_data),  # Size of image data
        22  # Offset to image data (6 + 16)
    )

    return ico_header + ico_entry + png_data


def generate_clock_icon(size=256):
    """Генерирует иконку часов с элементами Android TV"""
    pixels = []

    center_x = size // 2
    center_y = size // 2
    radius = size // 2 - 10

    for y in range(size):
        for x in range(size):
            # Расстояние от центра
            dx = x - center_x
            dy = y - center_y
            dist = (dx * dx + dy * dy) ** 0.5

            # Фон (прозрачный)
            if dist > radius + 5:
                pixels.append((0, 0, 0, 0))
            # Внешний круг (зелёный - цвет Android)
            elif dist > radius - 3:
                pixels.append((61, 220, 132, 255))  # Android green
            # Внутренний фон часов
            elif dist > radius - 8:
                pixels.append((45, 45, 45, 255))  # Dark gray
            else:
                # Белый фон циферблата
                inner_radius = radius - 8

                # Часовые метки
                is_mark = False
                for hour in range(12):
                    import math
                    angle = math.radians(hour * 30 - 90)
                    mark_x = center_x + int((inner_radius - 15) * math.cos(angle))
                    mark_y = center_y + int((inner_radius - 15) * math.sin(angle))
                    mark_dist = ((x - mark_x) ** 2 + (y - mark_y) ** 2) ** 0.5
                    if mark_dist < 5:
                        is_mark = True
                        break

                if is_mark:
                    pixels.append((61, 220, 132, 255))  # Android green marks
                else:
                    # Стрелки часов
                    # Часовая стрелка (указывает на 10)
                    import math
                    hour_angle = math.radians(10 * 30 - 90)
                    hour_len = inner_radius * 0.5
                    hour_end_x = center_x + int(hour_len * math.cos(hour_angle))
                    hour_end_y = center_y + int(hour_len * math.sin(hour_angle))

                    # Минутная стрелка (указывает на 2)
                    min_angle = math.radians(2 * 30 - 90)
                    min_len = inner_radius * 0.75
                    min_end_x = center_x + int(min_len * math.cos(min_angle))
                    min_end_y = center_y + int(min_len * math.sin(min_angle))

                    # Проверяем, находится ли точка на стрелке
                    def point_on_line(px, py, x1, y1, x2, y2, thickness):
                        line_len = ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5
                        if line_len == 0:
                            return False
                        t = max(0, min(1, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / (line_len ** 2)))
                        proj_x = x1 + t * (x2 - x1)
                        proj_y = y1 + t * (y2 - y1)
                        dist_to_line = ((px - proj_x) ** 2 + (py - proj_y) ** 2) ** 0.5
                        return dist_to_line < thickness

                    # Часовая стрелка (толще)
                    if point_on_line(x, y, center_x, center_y, hour_end_x, hour_end_y, 4):
                        pixels.append((50, 50, 50, 255))  # Dark gray
                    # Минутная стрелка (тоньше)
                    elif point_on_line(x, y, center_x, center_y, min_end_x, min_end_y, 2.5):
                        pixels.append((80, 80, 80, 255))  # Gray
                    # Центральная точка
                    elif dist < 6:
                        pixels.append((61, 220, 132, 255))  # Android green
                    else:
                        # Белый фон
                        pixels.append((255, 255, 255, 255))

    return pixels


def main():
    """Основная функция для генерации иконок"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)

    # Генерируем иконку 256x256
    size = 256
    print(f"Generating {size}x{size} icon...")
    pixels = generate_clock_icon(size)

    # Создаём PNG
    png_data = create_png(size, size, pixels)

    # Сохраняем PNG
    png_path = os.path.join(project_root, 'icon.png')
    with open(png_path, 'wb') as f:
        f.write(png_data)
    print(f"Created: {png_path}")

    # Создаём ICO (для Windows)
    ico_data = create_ico(png_data, size)
    ico_path = os.path.join(project_root, 'icon.ico')
    with open(ico_path, 'wb') as f:
        f.write(ico_data)
    print(f"Created: {ico_path}")

    print("Icon generation complete!")


if __name__ == '__main__':
    main()
