// mkicon draws the Ronin Browser icon (a shield with a sword slash) and
// writes a multi-size .ico file.
package main

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"math"
	"os"
)

func inShield(x, y float64) bool {
	// shield in unit square: flat top with rounded corners, pointed bottom
	if y < 0.08 || y > 0.94 || x < 0.12 || x > 0.88 {
		return false
	}
	if y < 0.55 {
		return true
	}
	// lower part narrows to a point at (0.5, 0.94)
	t := (y - 0.55) / (0.94 - 0.55)
	half := 0.38 * math.Cos(t*math.Pi/2)
	return math.Abs(x-0.5) <= half
}

func draw(size int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	const ss = 4 // supersampling
	for py := 0; py < size; py++ {
		for px := 0; px < size; px++ {
			var r, g, b, a float64
			for sy := 0; sy < ss; sy++ {
				for sx := 0; sx < ss; sx++ {
					x := (float64(px) + (float64(sx)+0.5)/ss) / float64(size)
					y := (float64(py) + (float64(sy)+0.5)/ss) / float64(size)
					if !inShield(x, y) {
						continue
					}
					// gradient from blue (top) to deep indigo (bottom)
					cr, cg, cb := 37+(30-37)*y, 99+(41-99)*y, 235+(140-235)*y
					// inner border
					inner := inShield(0.5+(x-0.5)*1.13, 0.08+(y-0.08)*1.08)
					if !inner {
						cr, cg, cb = 191, 219, 254
					}
					// diagonal sword slash
					d := math.Abs((x-0.5)+(y-0.5)*0.9) / math.Sqrt(1+0.81)
					along := (x - 0.5) - (y-0.5)*0.9
					if inner && d < 0.045 && along > -0.42 && along < 0.42 {
						cr, cg, cb = 255, 255, 255
					}
					// red sun dot
					if inner && math.Hypot(x-0.33, y-0.30) < 0.075 {
						cr, cg, cb = 239, 68, 68
					}
					r += cr
					g += cg
					b += cb
					a += 255
				}
			}
			n := float64(ss * ss)
			if a > 0 {
				cov := a / 255
				img.SetRGBA(px, py, color.RGBA{uint8(r / cov), uint8(g / cov), uint8(b / cov), uint8(a / n)})
			}
		}
	}
	return img
}

func main() {
	sizes := []int{16, 24, 32, 48, 64, 128, 256}
	var pngs [][]byte
	for _, s := range sizes {
		var buf bytes.Buffer
		png.Encode(&buf, draw(s))
		pngs = append(pngs, buf.Bytes())
		if s == 256 {
			os.WriteFile(os.Args[2], buf.Bytes(), 0o644)
		}
	}
	var out bytes.Buffer
	binary.Write(&out, binary.LittleEndian, [3]uint16{0, 1, uint16(len(sizes))})
	offset := 6 + 16*len(sizes)
	for i, s := range sizes {
		w := byte(s)
		if s == 256 {
			w = 0
		}
		out.Write([]byte{w, w, 0, 0})
		binary.Write(&out, binary.LittleEndian, uint16(1))
		binary.Write(&out, binary.LittleEndian, uint16(32))
		binary.Write(&out, binary.LittleEndian, uint32(len(pngs[i])))
		binary.Write(&out, binary.LittleEndian, uint32(offset))
		offset += len(pngs[i])
	}
	for _, p := range pngs {
		out.Write(p)
	}
	os.WriteFile(os.Args[1], out.Bytes(), 0o644)
}
