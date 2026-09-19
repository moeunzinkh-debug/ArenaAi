#!/usr/bin/env bash
#
# Regenerate every launcher-icon asset from one square source image.
#
#   ./tools/make-launcher-icons.sh path/to/source.png [options]
#
# Options
#   --fill F      share of the adaptive canvas the artwork occupies (default 1)
#                 use ~0.8 for art that carries its own frame or dark corners: it
#                 is then shrunk into the safe zone and faded into a glow field
#   --corner R    legacy icon corner radius as a fraction of its side (default 0.22)
#   --format X    webp (default) or png for the photographic layers
#   --quality Q   lossy WebP quality, 1-100 (default 90)
#   --preview P   write a contact sheet to P (default /tmp/icon-preview.png)
#   --res-dir D   res/ directory to write into (default app/src/main/res)
#
# The source may be any square-ish image ImageMagick can read (a JPEG from a
# phone, a PNG render, ...). It is centre-cropped and cut into:
#
#   * legacy mipmaps   mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher       (48-192 px)
#   * adaptive layers  mipmap-*/ic_launcher_{foreground,background,monochrome}
#
# Adaptive icons are 108x108dp, but launchers only reveal the central 66-72dp
# and mask it to a circle, squircle or rounded square, so the subject has to sit
# inside that safe zone. The background layer is a blurred copy of the artwork,
# which never clashes with the foreground whatever mask shape is used. The
# monochrome layer is the subject as a flat white silhouette, which is what
# Android 13+ paints for themed icons.
#
# Raster layers are WebP by default: the artwork is a photo-like render, and
# lossy WebP is ~16x smaller than the equivalent PNG at q90 with no visible
# difference at launcher sizes (the whole set lands near 80 KB instead of
# 765 KB). The monochrome layer is a two-tone silhouette, so it stays a
# lossless PNG. AAPT copies both as-is, which keeps builds reproducible.
#
# Requires ImageMagick 6 (`convert`). Nothing else.
set -euo pipefail

usage() {
    sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-1}"
}

SRC="${1:-}"
[[ -n "$SRC" && -f "$SRC" ]] || usage
shift

FILL=1
CORNER=0.22
FORMAT=webp
QUALITY=90
PREVIEW="/tmp/icon-preview.png"
RES=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fill)    FILL="$2"; shift 2 ;;
        --corner)  CORNER="$2"; shift 2 ;;
        --format)  FORMAT="$2"; shift 2 ;;
        --quality) QUALITY="$2"; shift 2 ;;
        --preview) PREVIEW="$2"; shift 2 ;;
        --res-dir) RES="$2"; shift 2 ;;
        -h|--help) usage 0 ;;
        *) echo "unknown option: $1" >&2; usage ;;
    esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES="${RES:-$ROOT/app/src/main/res}"

case "$FORMAT" in
    webp)
        convert -list format 2>/dev/null | grep -q 'WEBP' || {
            echo "this ImageMagick build has no WebP support; use --format png" >&2
            exit 1
        }
        ;;
    png) ;;
    *) echo "unknown --format: $FORMAT (webp or png)" >&2; exit 1 ;;
esac
EXT="$FORMAT"

# A resource name may only exist once per density folder, so a format switch has
# to clear whatever the previous run left behind.
OTHER=webp
[[ "$EXT" == webp ]] && OTHER=png

# ------------------------------------------------------------- density buckets
# Legacy launcher icon side (px) and adaptive canvas side (px), per bucket.
DENSITIES=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
LEGACY=(48 72 96 144 192)
ADAPTIVE=(108 162 216 324 432)

MONO=0.62        # share of the canvas used by the monochrome silhouette
FEATHER_MIN=0.94 # below this --fill the artwork is feathered into the field

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# encode <input> <output.ext> [convert args...]
# Writes <output> in the format implied by its extension.
encode() {
    local in="$1" out="$2"
    shift 2
    case "${out##*.}" in
        webp) convert "$in" "$@" -define webp:method=6 -define webp:exact=1 \
                  -quality "$QUALITY" "$out" ;;
        *)    convert "$in" "$@" -strip "$out" ;;
    esac
}

# ----------------------------------------------------------------- source prep
BASE="$WORK/base.png"
convert "$SRC" -resize 1024x1024^ -gravity center -extent 1024x1024! \
    -colorspace sRGB -strip "$BASE"

# ------------------------------------------------------------- adaptive layers
build_adaptive() {
    local S="$1" dir="$2" art blur mono c r

    # Background: blurred, gently dimmed copy of the artwork -> smooth glow field.
    blur="$(awk -v s="$S" 'BEGIN{printf "%.1f", s/22}')"
    encode "$BASE" "$dir/ic_launcher_background.$EXT" \
        -resize "${S}x${S}!" -blur "0x${blur}" -evaluate multiply 0.95

    # Foreground: artwork, optionally shrunk into the safe zone and faded so
    # framed sources do not leave a hard square edge under the launcher mask.
    art="$(awk -v s="$S" -v f="$FILL" 'BEGIN{printf "%d", s*f}')"
    convert "$BASE" -resize "${art}x${art}!" "$WORK/art.png"
    if awk -v f="$FILL" -v m="$FEATHER_MIN" 'BEGIN{exit !(f < m)}'; then
        c=$((art / 2))
        r="$(awk -v a="$art" 'BEGIN{printf "%d", a * 0.46}')"
        blur="$(awk -v a="$art" 'BEGIN{printf "%.1f", a / 5}')"
        convert -size "${art}x${art}" xc:black -fill white \
            -draw "circle ${c},${c} ${c},$((c - r))" -blur "0x${blur}" "$WORK/matte.png"
        convert "$WORK/art.png" \( "$WORK/matte.png" -alpha off \) \
            -compose CopyOpacity -composite "$WORK/art.png"
    fi
    encode "$WORK/art.png" "$dir/ic_launcher_foreground.$EXT" \
        -background none -gravity center -extent "${S}x${S}"

    # Monochrome: bright subject as a flat white silhouette. A low threshold plus
    # a morphology close merges the highlight gaps, so tinted themed icons do not
    # turn into speckle, while the dark visor keeps the eyes as negative space.
    mono="$(awk -v s="$S" -v m="$MONO" 'BEGIN{printf "%d", s*m}')"
    convert "$BASE" -resize "${mono}x${mono}!" -colorspace Gray -auto-level \
        -threshold 45% -morphology Close Disk:2 \
        -blur "0x$(awk -v m="$mono" 'BEGIN{printf "%.2f", m/150}')" -threshold 50% \
        -alpha off \( -size "${mono}x${mono}" xc:white \) \
        -compose CopyOpacity -composite "$WORK/mono.png"
    convert -size "${S}x${S}" xc:none "$WORK/mono.png" \
        -gravity center -compose over -composite -strip "$dir/ic_launcher_monochrome.png"
}

# --------------------------------------------------------------------- legacy
# Full-bleed artwork with transparent rounded corners: the pre-adaptive look.
build_legacy() {
    local N="$1" out="$2" r
    r="$(awk -v n="$N" -v c="$CORNER" 'BEGIN{printf "%d", n*c}')"
    encode "$BASE" "$out" -resize "${N}x${N}!" \
        \( -size "${N}x${N}" xc:none -fill white \
           -draw "roundrectangle 0,0 $((N - 1)),$((N - 1)) ${r},${r}" \) \
        -compose CopyOpacity -composite
}

for i in "${!DENSITIES[@]}"; do
    d="${DENSITIES[$i]}"
    dir="$RES/mipmap-$d"
    mkdir -p "$dir"
    for name in ic_launcher ic_launcher_foreground ic_launcher_background; do
        rm -f "$dir/$name.$OTHER"
    done
    build_adaptive "${ADAPTIVE[$i]}" "$dir"
    build_legacy "${LEGACY[$i]}" "$dir/ic_launcher.$EXT"
    echo "  mipmap-$d  <-  legacy ${LEGACY[$i]}px, adaptive ${ADAPTIVE[$i]}px ($EXT)"
done

# -------------------------------------------------------------------- preview
# Contact sheet: source, legacy icon, the adaptive icon through the three mask
# shapes launchers apply, and the monochrome layer.
XXX="$RES/mipmap-xxxhdpi"
ICON="$XXX/ic_launcher.$EXT"
FG="$XXX/ic_launcher_foreground.$EXT"
BG="$XXX/ic_launcher_background.$EXT"
MC="$XXX/ic_launcher_monochrome.png"
T=160

convert "$BG" "$FG" -gravity center -compose over -composite -resize "${T}x${T}" "$WORK/adaptive.png"
convert "$ICON" -resize "${T}x${T}" "$WORK/legacy.png"
convert "$WORK/adaptive.png" \
    \( -size "${T}x${T}" xc:none -fill white -draw "circle $((T / 2)),$((T / 2)) $((T / 2)),2" \) \
    -alpha off -compose CopyOpacity -composite "$WORK/circle.png"
convert "$WORK/adaptive.png" \
    \( -size "${T}x${T}" xc:none -fill white \
       -draw "roundrectangle 0,0 $((T - 1)),$((T - 1)) 38,38" \) \
    -alpha off -compose CopyOpacity -composite "$WORK/squircle.png"
convert -size "${T}x${T}" xc:'#0b1220' \( "$MC" -resize "${T}x${T}" \) \
    -gravity center -compose over -composite "$WORK/mc.png"
convert "$BASE" -resize "${T}x${T}" "$WORK/source.png"
convert \( "$WORK/source.png" "$WORK/legacy.png" "$WORK/circle.png" \
    "$WORK/squircle.png" "$WORK/adaptive.png" "$WORK/mc.png" \) +append \
    -bordercolor '#0b1220' -border 14 -strip "$PREVIEW"

echo
echo "done."
echo "  assets  : $RES/mipmap-*/  ($EXT raster layers, PNG monochrome)"
echo "  preview : $PREVIEW  (source | legacy | circle | squircle | adaptive | monochrome)"
