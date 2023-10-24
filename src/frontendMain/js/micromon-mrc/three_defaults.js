import { hexToRgb } from "./three_help"

export const ISOTHRESHOLD    = 0.6
export const FIRST_COLOR     = "#3cc2f8"
export const SECOND_COLOR    = "#dd43e0"
export const RENDERSTYLE     = "iso"
export const CAMERA_POSITION = [-1, -1, 1]
export const CLIM            = [0.58, 1]
export const CLOSE_GUI       = false
export const SHARPNESS       = 0.50
export const COLORMAP        = "viridis"
export const STEPS           = 800
export const GUI_HIDABLE     = true // by pressing of the 'h' key

////////////////////////////////////////////////////////////////////////////////

export const CAMERA_DISTANCE = Math.sqrt( CAMERA_POSITION.reduce(
        (partialSum, value) => partialSum + (value * value), /* (initial value) */ 0
    )
)

export const FIRST_COLOR_VALUES = hexToRgb(FIRST_COLOR)
export const SECOND_COLOR_VALUES = hexToRgb(SECOND_COLOR)