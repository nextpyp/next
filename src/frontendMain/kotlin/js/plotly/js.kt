@file:Suppress("INTERFACE_WITH_SUPERCLASS", "OVERRIDING_FINAL_MEMBER", "RETURN_TYPE_MISMATCH_ON_OVERRIDE", "CONFLICTING_OVERLOADS", "EXTERNAL_DELEGATION")

// NOTE: KotlinJs bindings for Plotly.js automatically generated using Dukat:
// https://github.com/kotlin/dukat
// then modified by hand to actually work correctly, since Dukat isn't perfect

package js.plotly

import kotlin.js.*
import org.khronos.webgl.*
import org.w3c.dom.*
import org.w3c.dom.events.*

external interface StaticPlots {
    fun resize(root: String)
    fun resize(root: HTMLElement)
}

external var Plots: StaticPlots

external interface Point {
    var x: Number
    var y: Number
    var z: Number
}

external interface PlotScatterDataPoint {
    var curveNumber: Number
    var data: PlotData
    var pointIndex: Number
    var pointNumber: Number
    var x: Number
    var xaxis: LayoutAxis
    var y: Number
    var yaxis: LayoutAxis
}

external interface PlotDatum {
    var curveNumber: Number
    var data: PlotData
    var pointIndex: Number
    var pointNumber: Number
    var x: dynamic /* String | Number | Date | Nothing? */
    var xaxis: LayoutAxis
    var y: dynamic /* String | Number | Date | Nothing? */
    var yaxis: LayoutAxis
}

external interface PlotMouseEvent {
    var points: Array<PlotDatum>
    var event: MouseEvent
}

external interface PlotCoordinate {
    var x: Number
    var y: Number
    var pointNumber: Number
}

external interface SelectionRange {
    var x: Array<Number>
    var y: Array<Number>
}

typealias PlotSelectedData = PlotDatum

external interface PlotSelectionEvent {
    var points: Array<PlotDatum>
    var range: SelectionRange?
        get() = definedExternally
        set(value) = definedExternally
    var lassoPoints: SelectionRange?
        get() = definedExternally
        set(value) = definedExternally
}

external interface PlotAxis {
    var range: dynamic /* JsTuple<Number, Number> */
    var autorange: Boolean
}

external interface PlotScene {
    var center: Point
    var eye: Point
    var up: Point
}

external interface PlotRelayoutEvent {
    var xaxis: PlotAxis
    var yaxis: PlotAxis
    var scene: PlotScene
}

external interface ClickAnnotationEvent {
    var index: Number
    var annotation: Annotations
    var fullAnnotation: Annotations
    var event: MouseEvent
}

external interface `T$0` {
    var duration: Number
    var redraw: Boolean
}

external interface `T$1` {
    var frame: `T$0`
    var transition: Transition
}

external interface FrameAnimationEvent {
    var name: String
    var frame: Frame
    var animation: `T$1`
}

external interface LegendClickEvent {
    var event: MouseEvent
    var node: PlotlyHTMLElement
    var curveNumber: Number
    var expandedIndex: Number
    var data: Array<Data>
    var layout: Layout
    var frames: Array<Frame>
    var config: Config
    var fullData: Array<Data>
    var fullLayout: Layout
}

external interface SliderChangeEvent {
    var slider: Slider
    var step: SliderStep
    var interaction: Boolean
    var previousActive: Number
}

external interface SliderStartEvent {
    var slider: Slider
}

external interface SliderEndEvent {
    var slider: Slider
    var step: SliderStep
}

external interface BeforePlotEvent {
    var data: Array<Data>
    var layout: Layout
    var config: Config
}

external interface PlotlyHTMLElement : HTMLElement {
	/* for reference
    fun on(event: String /* 'plotly_click' */, callback: (event: PlotMouseEvent) -> Unit)
    fun on(event: String /* 'plotly_hover' */, callback: (event: PlotMouseEvent) -> Unit)
    fun on(event: String /* 'plotly_unhover' */, callback: (event: PlotMouseEvent) -> Unit)
    fun on(event: String /* 'plotly_selecting' */, callback: (event: PlotSelectionEvent) -> Unit)
    fun on(event: String /* 'plotly_selected' */, callback: (event: PlotSelectionEvent) -> Unit)
    fun on(event: String /* 'plotly_restyle' */, callback: (data: PlotRestyleEvent) -> Unit)
    fun on(event: String /* 'plotly_relayout' */, callback: (event: PlotRelayoutEvent) -> Unit)
    fun on(event: String /* 'plotly_clickannotation' */, callback: (event: ClickAnnotationEvent) -> Unit)
    fun on(event: String /* 'plotly_animatingframe' */, callback: (event: FrameAnimationEvent) -> Unit)
    fun on(event: String /* 'plotly_legendclick' */, callback: (event: LegendClickEvent) -> Boolean)
    fun on(event: String /* 'plotly_legenddoubleclick' */, callback: (event: LegendClickEvent) -> Boolean)
    fun on(event: String /* 'plotly_sliderchange' */, callback: (event: SliderChangeEvent) -> Unit)
    fun on(event: String /* 'plotly_sliderend' */, callback: (event: SliderEndEvent) -> Unit)
    fun on(event: String /* 'plotly_sliderstart' */, callback: (event: SliderStartEvent) -> Unit)
    fun on(event: String /* 'plotly_event' */, callback: (data: Any) -> Unit)
    fun on(event: String /* 'plotly_beforeplot' */, callback: (event: BeforePlotEvent) -> Boolean)
    fun on(event: String /* 'plotly_afterexport' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_afterplot' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_animated' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_animationinterrupted' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_autosize' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_beforeexport' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_deselect' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_doubleclick' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_framework' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_redraw' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_transitioning' */, callback: () -> Unit)
    fun on(event: String /* 'plotly_transitioninterrupted' */, callback: () -> Unit)
    */
	fun <T> on(event: String, callback: (T) -> Unit)
    var removeAllListeners: (handler: String) -> Unit
}

fun PlotlyHTMLElement.onClick(callback: (PlotMouseEvent) -> Unit) = on<PlotMouseEvent>("plotly_click") { callback(it) }
fun PlotlyHTMLElement.onHover(callback: (PlotMouseEvent) -> Unit) = on<PlotMouseEvent>("plotly_hover") { callback(it) }
fun PlotlyHTMLElement.onUnhover(callback: (PlotMouseEvent) -> Unit) = on<PlotMouseEvent>("plotly_unhover") { callback(it) }

external interface ToImgopts {
    var format: dynamic /* 'jpeg' | 'png' | 'webp' | 'svg' */
    var width: Number
    var height: Number
}

external interface DownloadImgopts {
    var format: dynamic /* 'jpeg' | 'png' | 'webp' | 'svg' */
    var width: Number
    var height: Number
    var filename: String
}


@JsModule("plotly.js/dist/plotly")
@JsNonModule
external object Plotly {

	fun newPlot(root: String, data: Array<Data>, layout: Layout? = definedExternally /* null */, config: Config? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun newPlot(root: HTMLElement, data: Array<Data>, layout: Layout? = definedExternally /* null */, config: Config? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun plot(root: String, data: Array<Data>, layout: Layout? = definedExternally /* null */, config: Config? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun plot(root: HTMLElement, data: Array<Data>, layout: Layout? = definedExternally /* null */, config: Config? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun relayout(root: String, layout: Layout): Promise<PlotlyHTMLElement>

	fun relayout(root: HTMLElement, layout: Layout): Promise<PlotlyHTMLElement>

	fun redraw(root: String): Promise<PlotlyHTMLElement>

	fun redraw(root: HTMLElement): Promise<PlotlyHTMLElement>

	fun purge(root: String)

	fun purge(root: HTMLElement)

	var d3: Any

	fun restyle(root: String, aobj: Data, traces: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun restyle(root: String, aobj: Data, traces: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun restyle(root: HTMLElement, aobj: Data, traces: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun restyle(root: HTMLElement, aobj: Data, traces: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun update(root: String, traceUpdate: Data, layoutUpdate: Layout, traces: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun update(root: String, traceUpdate: Data, layoutUpdate: Layout, traces: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun update(root: HTMLElement, traceUpdate: Data, layoutUpdate: Layout, traces: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun update(root: HTMLElement, traceUpdate: Data, layoutUpdate: Layout, traces: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: String, traces: Data, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: String, traces: Data, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: String, traces: Array<Data>, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: String, traces: Array<Data>, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: HTMLElement, traces: Data, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: HTMLElement, traces: Data, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: HTMLElement, traces: Array<Data>, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addTraces(root: HTMLElement, traces: Array<Data>, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun deleteTraces(root: String, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun deleteTraces(root: String, indices: Number): Promise<PlotlyHTMLElement>

	fun deleteTraces(root: HTMLElement, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun deleteTraces(root: HTMLElement, indices: Number): Promise<PlotlyHTMLElement>

	fun moveTraces(root: String, currentIndices: Array<Number>, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: String, currentIndices: Array<Number>, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: String, currentIndices: Number, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: String, currentIndices: Number, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: HTMLElement, currentIndices: Array<Number>, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: HTMLElement, currentIndices: Array<Number>, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: HTMLElement, currentIndices: Number, newIndices: Array<Number>? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun moveTraces(root: HTMLElement, currentIndices: Number, newIndices: Number? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	/* these aren't right... update needs to be a plain js object
	fun extendTraces(root: String, update: Data, indices: Number): Promise<PlotlyHTMLElement>
	fun extendTraces(root: String, update: Data, indices: Array<Number>): Promise<PlotlyHTMLElement>
	fun extendTraces(root: String, update: Array<Data>, indices: Number): Promise<PlotlyHTMLElement>
	fun extendTraces(root: String, update: Array<Data>, indices: Array<Number>): Promise<PlotlyHTMLElement>
	fun extendTraces(root: HTMLElement, update: Data, indices: Number): Promise<PlotlyHTMLElement>
	fun extendTraces(root: HTMLElement, update: Data, indices: Array<Number>): Promise<PlotlyHTMLElement>
	fun extendTraces(root: HTMLElement, update: Array<Data>, indices: Number): Promise<PlotlyHTMLElement>
	fun extendTraces(root: HTMLElement, update: Array<Data>, indices: Array<Number>): Promise<PlotlyHTMLElement>
	*/
	fun extendTraces(root: HTMLElement, update: dynamic, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun prependTraces(root: String, update: Data, indices: Number): Promise<PlotlyHTMLElement>

	fun prependTraces(root: String, update: Data, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun prependTraces(root: String, update: Array<Data>, indices: Number): Promise<PlotlyHTMLElement>

	fun prependTraces(root: String, update: Array<Data>, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun prependTraces(root: HTMLElement, update: Data, indices: Number): Promise<PlotlyHTMLElement>

	fun prependTraces(root: HTMLElement, update: Data, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun prependTraces(root: HTMLElement, update: Array<Data>, indices: Number): Promise<PlotlyHTMLElement>

	fun prependTraces(root: HTMLElement, update: Array<Data>, indices: Array<Number>): Promise<PlotlyHTMLElement>

	fun toImage(root: String, opts: ToImgopts): Promise<String>

	fun toImage(root: HTMLElement, opts: ToImgopts): Promise<String>

	fun downloadImage(root: String, opts: DownloadImgopts): Promise<String>

	fun downloadImage(root: HTMLElement, opts: DownloadImgopts): Promise<String>

	fun react(root: String, data: Array<Data>, layout: Layout? = definedExternally /* null */, config: Config? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun react(root: HTMLElement, data: Array<Data>, layout: Layout? = definedExternally /* null */, config: Config? = definedExternally /* null */): Promise<PlotlyHTMLElement>

	fun addFrames(root: String, frames: Array<Frame>): Promise<PlotlyHTMLElement>

	fun addFrames(root: HTMLElement, frames: Array<Frame>): Promise<PlotlyHTMLElement>

	fun deleteFrames(root: String, frames: Array<Number>): Promise<PlotlyHTMLElement>

	fun deleteFrames(root: HTMLElement, frames: Array<Number>): Promise<PlotlyHTMLElement>


	fun restyle(root: String, aobj: Data): Promise<PlotlyHTMLElement>

	fun restyle(root: HTMLElement, aobj: Data): Promise<PlotlyHTMLElement>

	fun update(root: String, traceUpdate: Data, layoutUpdate: Layout): Promise<PlotlyHTMLElement>

	fun update(root: HTMLElement, traceUpdate: Data, layoutUpdate: Layout): Promise<PlotlyHTMLElement>

	fun addTraces(root: String, traces: Data): Promise<PlotlyHTMLElement>

	fun addTraces(root: String, traces: Array<Data>): Promise<PlotlyHTMLElement>

	fun addTraces(root: HTMLElement, traces: Data): Promise<PlotlyHTMLElement>

	fun addTraces(root: HTMLElement, traces: Array<Data>): Promise<PlotlyHTMLElement>

	fun moveTraces(root: String, currentIndices: Array<Number>): Promise<PlotlyHTMLElement>

	fun moveTraces(root: String, currentIndices: Number): Promise<PlotlyHTMLElement>

	fun moveTraces(root: HTMLElement, currentIndices: Array<Number>): Promise<PlotlyHTMLElement>

	fun moveTraces(root: HTMLElement, currentIndices: Number): Promise<PlotlyHTMLElement>
}

external interface `T$2` {
    var text: String?
        get() = definedExternally
        set(value) = definedExternally
    var font: Font?
        get() = definedExternally
        set(value) = definedExternally
    var xref: dynamic /* 'container' | 'paper' */
        get() = definedExternally
        set(value) = definedExternally
    var yref: dynamic /* 'container' | 'paper' */
        get() = definedExternally
        set(value) = definedExternally
    var x: Number?
        get() = definedExternally
        set(value) = definedExternally
    var y: Number?
        get() = definedExternally
        set(value) = definedExternally
    var xanchor: dynamic /* 'auto' | 'left' | 'center' | 'right' */
        get() = definedExternally
        set(value) = definedExternally
    var yanchor: dynamic /* 'auto' | 'top' | 'middle' | 'bottom' */
        get() = definedExternally
        set(value) = definedExternally
    var pad: Padding?
        get() = definedExternally
        set(value) = definedExternally
}

external interface Layout {
    var title: dynamic /* String | `T$2` */
    var titlefont: Font
    var autosize: Boolean
    var showlegend: Boolean
    var paper_bgcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var plot_bgcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var separators: String
    var hidesources: Boolean
    var xaxis: LayoutAxis
    var xaxis2: LayoutAxis
    var xaxis3: LayoutAxis
    var xaxis4: LayoutAxis
    var xaxis5: LayoutAxis
    var xaxis6: LayoutAxis
    var xaxis7: LayoutAxis
    var xaxis8: LayoutAxis
    var xaxis9: LayoutAxis
    var yaxis: LayoutAxis
    var yaxis2: LayoutAxis
    var yaxis3: LayoutAxis
    var yaxis4: LayoutAxis
    var yaxis5: LayoutAxis
    var yaxis6: LayoutAxis
    var yaxis7: LayoutAxis
    var yaxis8: LayoutAxis
    var yaxis9: LayoutAxis
    var margin: Margin
    var height: Number
    var width: Number
    var hovermode: dynamic /* 'closest' | 'x' | 'y' | false */
    var hoverlabel: HoverLabel
    var calendar: dynamic /* 'gregorian' | 'chinese' | 'coptic' | 'discworld' | 'ethiopian' | 'hebrew' | 'islamic' | 'julian' | 'mayan' | 'nanakshahi' | 'nepali' | 'persian' | 'jalali' | 'taiwan' | 'thai' | 'ummalqura' */
    var ternary: Any
    var geo: Any
    var mapbox: Any
    var radialaxis: Axis
    var angularaxis: Any
    var direction: dynamic /* 'clockwise' | 'counterclockwise' */
    var dragmode: dynamic /* 'zoom' | 'pan' | 'select' | 'lasso' | 'orbit' | 'turntable' */
    var orientation: Number
    var annotations: Array<Annotations>
    var shapes: Array<Shape>
    var images: Array<Image>
    var updatemenus: Any
    var sliders: Array<Slider>
    var legend: Legend
    var font: Font
    var scene: Scene
    var barmode: dynamic /* "stack" | "group" | "overlay" | "relative" */
    var bargap: Number
    var bargroupgap: Number
    var selectdirection: dynamic /* 'h' | 'v' | 'd' | 'any' */
}

external interface Legend : Label {
    var traceorder: dynamic /* 'grouped' | 'normal' | 'reversed' */
    var x: Number
    var y: Number
    var borderwidth: Number
    var orientation: dynamic /* 'v' | 'h' */
    var tracegroupgap: Number
    var xanchor: dynamic /* 'auto' | 'left' | 'center' | 'right' */
    var yanchor: dynamic /* 'auto' | 'top' | 'middle' | 'bottom' */
}

external interface TitleFormat {
    var text: String
    var standoff: Number
}

external interface Axis {
    var visible: Boolean
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var title: dynamic /* String | Title */
    var titlefont: Font
    var type: dynamic /* '-' | 'linear' | 'log' | 'date' | 'category' */
    var autorange: dynamic /* true | false | 'reversed' */
    var rangemode: dynamic /* 'normal' | 'tozero' | 'nonnegative' */
    var range: Array<Any>
    var tickmode: dynamic /* 'auto' | 'linear' | 'array' */
    var nticks: Number
    var tick0: dynamic /* Number | String */
    var dtick: dynamic /* Number | String */
    var tickvals: Array<Any>
    var ticktext: Array<String>
    var ticks: dynamic /* 'outside' | 'inside' | '' */
    var mirror: dynamic /* true | 'ticks' | false | 'all' | 'allticks' */
    var ticklen: Number
    var tickwidth: Number
    var tickcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var showticklabels: Boolean
    var showspikes: Boolean
    var spikecolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var spikethickness: Number
    var categoryorder: dynamic /* 'trace' | 'category ascending' | 'category descending' | 'array' */
    var categoryarray: Array<Any>
    var tickfont: Font
    var tickangle: Number
    var tickprefix: String
    var showtickprefix: dynamic /* 'all' | 'first' | 'last' | 'none' */
    var ticksuffix: String
    var showticksuffix: dynamic /* 'all' | 'first' | 'last' | 'none' */
    var showexponent: dynamic /* 'all' | 'first' | 'last' | 'none' */
    var exponentformat: dynamic /* 'none' | 'e' | 'E' | 'power' | 'SI' | 'B' */
    var separatethousands: Boolean
    var tickformat: String
    var hoverformat: String
    var showline: Boolean
    var linecolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var linewidth: Number
    var showgrid: Boolean
    var gridcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var gridwidth: Number
    var zeroline: Boolean
    var zerolinecolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var zerolinewidth: Number
    var calendar: dynamic /* 'gregorian' | 'chinese' | 'coptic' | 'discworld' | 'ethiopian' | 'hebrew' | 'islamic' | 'julian' | 'mayan' | 'nanakshahi' | 'nepali' | 'persian' | 'jalali' | 'taiwan' | 'thai' | 'ummalqura' */
}

external interface LayoutAxis : Axis {
    var fixedrange: Boolean
    var scaleanchor: dynamic /* 'x' | 'x2' | 'x3' | 'x4' | 'x5' | 'x6' | 'x7' | 'x8' | 'x9' | 'y' | 'y2' | 'y3' | 'y4' | 'y5' | 'y6' | 'y7' | 'y8' | 'y9' */
    var scaleratio: Number
    var constrain: dynamic /* 'range' | 'domain' */
    var constraintoward: dynamic /* 'left' | 'center' | 'right' | 'top' | 'middle' | 'bottom' */
    var spikedash: String
    var spikemode: String
    var anchor: dynamic /* 'free' | 'x' | 'x2' | 'x3' | 'x4' | 'x5' | 'x6' | 'x7' | 'x8' | 'x9' | 'y' | 'y2' | 'y3' | 'y4' | 'y5' | 'y6' | 'y7' | 'y8' | 'y9' */
    var side: dynamic /* 'top' | 'bottom' | 'left' | 'right' */
    var overlaying: dynamic /* 'free' | 'x' | 'x2' | 'x3' | 'x4' | 'x5' | 'x6' | 'x7' | 'x8' | 'x9' | 'y' | 'y2' | 'y3' | 'y4' | 'y5' | 'y6' | 'y7' | 'y8' | 'y9' */
    var layer: dynamic /* 'above traces' | 'below traces' */
    var domain: Array<Number>
    var position: Number
    var rangeslider: RangeSlider
    var rangeselector: RangeSelector
    var automargin: Boolean
}

external interface SceneAxis : Axis {
    var spikesides: Boolean
    var showbackground: Boolean
    var backgroundcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var showaxeslabels: Boolean
}

external interface ShapeLine {
    var color: String
    var width: Number
    var dash: dynamic /* 'solid' | 'dot' | 'dash' | 'longdash' | 'dashdot' | 'longdashdot' */
}

external interface Shape {
    var visible: Boolean
    var layer: dynamic /* 'below' | 'above' */
    var type: dynamic /* 'rect' | 'circle' | 'line' | 'path' */
    var path: String
    var xref: dynamic /* 'x' | 'paper' */
    var yref: dynamic /* 'paper' | 'y' */
    var x0: dynamic /* String | Number | Date | Nothing? */
    var y0: dynamic /* String | Number | Date | Nothing? */
    var x1: dynamic /* String | Number | Date | Nothing? */
    var y1: dynamic /* String | Number | Date | Nothing? */
    var fillcolor: String
    var opacity: Number
    var line: ShapeLine
}

external interface Margin {
    var t: Number
    var b: Number
    var l: Number
    var r: Number
}

typealias ButtonClickEvent = (gd: PlotlyHTMLElement, ev: MouseEvent) -> Unit

external interface Icon {
    var width: Number
    var path: String
    var ascent: Number
    var descent: Number
}

external interface ModeBarButton {
    var name: String
    var title: String
    var icon: dynamic /* String | Icon */
    var gravity: String?
        get() = definedExternally
        set(value) = definedExternally
    var click: ButtonClickEvent
    var attr: String?
        get() = definedExternally
        set(value) = definedExternally
    var `val`: Any?
        get() = definedExternally
        set(value) = definedExternally
    var toggle: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface ErrorOptions {
    var visible: Boolean
    var symmetric: Boolean
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var thickness: Number
    var width: Number
    var opacity: Number
}

typealias Data = PlotData

typealias DataTransform = Transform

typealias ScatterData = PlotData

external interface `T$3` {
    var start: dynamic /* Number | String */
    var end: dynamic /* Number | String */
    var size: dynamic /* Number | String */
}

external interface PlotData {
    var type: dynamic /* 'bar' | 'box' | 'candlestick' | 'choropleth' | 'contour' | 'heatmap' | 'histogram' | 'mesh3d' | 'ohlc' | 'parcoords' | 'pie' | 'pointcloud' | 'scatter' | 'scatter3d' | 'scattergeo' | 'scattergl' | 'scatterpolar' | 'scatterternary' | 'surface' */
    var x: dynamic /* Array<dynamic /* String | Number | Date | Nothing? */> | Array<Array<dynamic /* String | Number | Date | Nothing? */>> | Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Uint32Array | Uint8ClampedArray | Float32Array | Float64Array */
    var y: dynamic /* Array<dynamic /* String | Number | Date | Nothing? */> | Array<Array<dynamic /* String | Number | Date | Nothing? */>> | Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Uint32Array | Uint8ClampedArray | Float32Array | Float64Array */
    var z: dynamic /* Array<dynamic /* String | Number | Date | Nothing? */> | Array<Array<dynamic /* String | Number | Date | Nothing? */>> | Array<Array<Array<dynamic /* String | Number | Date | Nothing? */>>> | Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Uint32Array | Uint8ClampedArray | Float32Array | Float64Array */
    var xy: Float32Array
    var error_x: ErrorOptions? /* ErrorOptions & dynamic */
    var error_y: ErrorOptions? /* ErrorOptions & dynamic */
    var xaxis: String
    var yaxis: String
    var text: dynamic /* String | Array<String> */
    var line: ScatterLine
    var marker: PlotMarker
    var mode: dynamic /* 'lines' | 'markers' | 'text' | 'lines+markers' | 'text+markers' | 'text+lines' | 'text+lines+markers' | 'none' */
    var hoveron: dynamic /* 'points' | 'fills' */
    var hoverinfo: dynamic /* 'all' | 'name' | 'none' | 'skip' | 'text' | 'x' | 'x+text' | 'x+name' | 'x+y' | 'x+y+text' | 'x+y+name' | 'x+y+z' | 'x+y+z+text' | 'x+y+z+name' | 'y+name' | 'y+x' | 'y+text' | 'y+x+text' | 'y+x+name' | 'y+z' | 'y+z+text' | 'y+z+name' | 'y+x+z' | 'y+x+z+text' | 'y+x+z+name' | 'z+x' | 'z+x+text' | 'z+x+name' | 'z+y+x' | 'z+y+x+text' | 'z+y+x+name' | 'z+x+y' | 'z+x+y+text' | 'z+x+y+name' */
    var hoverlabel: HoverLabel
    var hovertemplate: dynamic /* String | Array<String> */
    var textinfo: dynamic /* 'label' | 'label+text' | 'label+value' | 'label+percent' | 'label+text+value' | 'label+text+percent' | 'label+value+percent' | 'text' | 'text+value' | 'text+percent' | 'text+value+percent' | 'value' | 'value+percent' | 'percent' | 'none' */
    var textposition: dynamic /* "top left" | "top center" | "top right" | "middle left" | "middle center" | "middle right" | "bottom left" | "bottom center" | "bottom right" | "inside" */
    var fill: dynamic /* 'none' | 'tozeroy' | 'tozerox' | 'tonexty' | 'tonextx' | 'toself' | 'tonext' */
    var fillcolor: String
    var legendgroup: String
    var name: String
    var stackgroup: String
    var connectgaps: Boolean
    var visible: dynamic /* Boolean | 'legendonly' */
    var transforms: Array<DataTransform>
    var orientation: dynamic /* 'v' | 'h' */
    var width: dynamic /* Number | Array<Number> */
    var boxmean: dynamic /* Boolean | 'sd' */
    var colorbar: ColorBar
    var colorscale: dynamic /* String | Array<String> | Array<dynamic /* JsTuple<Number, String> */> */
    var zsmooth: dynamic /* 'fast' | 'best' | false */
    var ygap: Number
    var xgap: Number
    var transpose: Boolean
    var autobinx: Boolean
    var xbins: `T$3`
    var values: Array<dynamic /* String | Number | Date | Nothing? */>
    var labels: Array<dynamic /* String | Number | Date | Nothing? */>
    var hole: Number
    var rotation: Number
    var theta: Array<dynamic /* String | Number | Date | Nothing? */>
    var r: Array<dynamic /* String | Number | Date | Nothing? */>

	// histogram stuff
	var histnorm: String
	var nbinsx: Number

    // other layout stuff
    var showlegend: Boolean
}

external interface TransformStyle {
    var target: dynamic /* Number | String | Array<Number> | Array<String> */
    var value: PlotData
}

external interface TransformAggregation {
    var target: String
    var func: dynamic /* 'count' | 'sum' | 'avg' | 'median' | 'mode' | 'rms' | 'stddev' | 'min' | 'max' | 'first' | 'last' */
        get() = definedExternally
        set(value) = definedExternally
    var funcmode: dynamic /* 'sample' | 'population' */
        get() = definedExternally
        set(value) = definedExternally
    var enabled: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface Transform {
    var type: dynamic /* 'aggregate' | 'filter' | 'groupby' | 'sort' */
    var enabled: Boolean
    var target: dynamic /* Number | String | Array<Number> | Array<String> */
    var operation: String
    var aggregations: Array<TransformAggregation>
    var preservegaps: Boolean
    var groups: dynamic /* String | Array<Number> | Array<String> */
    var nameformat: String
    var styles: Array<TransformStyle>
    var value: Any
    var order: dynamic /* 'ascending' | 'descending' */
}

external interface `T$4` {
    var dtickrange: Array<Any>
    var value: String
}

external interface ColorBar {
    var thicknessmode: dynamic /* 'fraction' | 'pixels' */
    var thickness: Number
    var lenmode: dynamic /* 'fraction' | 'pixels' */
    var len: Number
    var x: Number
    var xanchor: dynamic /* 'left' | 'center' | 'right' */
    var xpad: Number
    var y: Number
    var yanchor: dynamic /* 'top' | 'middle' | 'bottom' */
    var ypad: Number
    var outlinecolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var outlinewidth: Number
    var bordercolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var borderwidth: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var bgcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var tickmode: dynamic /* 'auto' | 'linear' | 'array' */
    var nticks: Number
    var tick0: dynamic /* Number | String */
    var dtick: dynamic /* Number | String */
    var tickvals: dynamic /* Array<dynamic /* String | Number | Date | Nothing? */> | Array<Array<dynamic /* String | Number | Date | Nothing? */>> | Array<Array<Array<dynamic /* String | Number | Date | Nothing? */>>> | Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Uint32Array | Uint8ClampedArray | Float32Array | Float64Array */
    var ticktext: dynamic /* Array<dynamic /* String | Number | Date | Nothing? */> | Array<Array<dynamic /* String | Number | Date | Nothing? */>> | Array<Array<Array<dynamic /* String | Number | Date | Nothing? */>>> | Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Uint32Array | Uint8ClampedArray | Float32Array | Float64Array */
    var ticks: dynamic /* 'outside' | 'inside' | '' */
    var ticklen: Number
    var tickwidth: Number
    var tickcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var showticklabels: Boolean
    var tickfont: Font
    var tickangle: Number
    var tickformat: String
    var tickformatstops: `T$4`
    var tickprefix: String
    var showtickprefix: dynamic /* 'all' | 'first' | 'last' | 'none' */
    var ticksuffix: String
    var showticksuffix: dynamic /* 'all' | 'first' | 'last' | 'none' */
    var separatethousands: Boolean
    var exponentformat: dynamic /* 'none' | 'e' | 'E' | 'power' | 'SI' | 'B' */
    var showexponent: dynamic /* 'all' | 'first' | 'last' | 'none' */
    var title: String
    var titlefont: Font
    var titleside: dynamic /* 'right' | 'top' | 'bottom' */
    var tickvalssrc: Any
    var ticktextsrc: Any
}

external interface `T$5` {
    var type: dynamic /* 'radial' | 'horizontal' | 'vertical' | 'none' */
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var typesrc: Any
    var colorsrc: Any
}

external interface PlotMarker {
    var symbol: dynamic /* String | Array<String> */
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> | Array<dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */> */
    var colors: Array<dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */>
    var colorscale: dynamic /* String | Array<String> | Array<dynamic /* JsTuple<Number, String> */> */
    var cauto: Boolean
    var cmax: Number
    var cmin: Number
    var autocolorscale: Boolean
    var reversescale: Boolean
    var opacity: dynamic /* Number | Array<Number> */
    var size: dynamic /* Number | Array<Number> */
    var maxdisplayed: Number
    var sizeref: Number
    var sizemax: Number
    var sizemin: Number
    var sizemode: dynamic /* 'diameter' | 'area' */
    var showscale: Boolean
    var line: ScatterMarkerLine
    var width: Number
    var colorbar: ColorBar
    var gradient: `T$5`
}

typealias ScatterMarker = PlotMarker

external interface ScatterMarkerLine {
    var width: dynamic /* Number | Array<Number> */
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var colorscale: dynamic /* String | Array<String> | Array<dynamic /* JsTuple<Number, String> */> */
    var cauto: Boolean
    var cmax: Number
    var cmin: Number
    var autocolorscale: Boolean
    var reversescale: Boolean
}

external interface ScatterLine {
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var width: Number
    var dash: dynamic /* 'solid' | 'dot' | 'dash' | 'longdash' | 'dashdot' | 'longdashdot' */
    var shape: dynamic /* 'linear' | 'spline' | 'hv' | 'vh' | 'hvh' | 'vhv' */
    var smoothing: Number
    var simplify: Boolean
}

external interface Font {
    var family: String
    var size: Number
    var color: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
}

external interface Edits {
    var annotationPosition: Boolean
    var annotationTail: Boolean
    var annotationText: Boolean
    var axisTitleText: Boolean
    var colorbarPosition: Boolean
    var colorbarTitleText: Boolean
    var legendPosition: Boolean
    var legendText: Boolean
    var shapePosition: Boolean
    var titleText: Boolean
}

external interface `T$6` {
    var filename: String?
        get() = definedExternally
        set(value) = definedExternally
    var scale: Number?
        get() = definedExternally
        set(value) = definedExternally
    var format: dynamic /* 'png' | 'svg' | 'jpeg' | 'webp' */
        get() = definedExternally
        set(value) = definedExternally
    var height: Number?
        get() = definedExternally
        set(value) = definedExternally
    var width: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface Config {
    var toImageButtonOptions: `T$6`
    var staticPlot: Boolean
    var editable: Boolean
    var edits: Edits
    var autosizable: Boolean
    var queueLength: Number
    var fillFrame: Boolean
    var frameMargins: Number
    var scrollZoom: Boolean
    var doubleClick: dynamic /* 'reset+autosize' | 'reset' | 'autosize' | false */
    var showTips: Boolean
    var showAxisDragHandles: Boolean
    var showAxisRangeEntryBoxes: Boolean
    var showLink: Boolean
    var sendData: Boolean
    var linkText: String
    var showSources: Boolean
    var displayModeBar: dynamic /* 'hover' | Boolean */
    var modeBarButtonsToRemove: Array<dynamic /* 'lasso2d' | 'select2d' | 'sendDataToCloud' | 'autoScale2d' | 'zoom2d' | 'pan2d' | 'zoomIn2d' | 'zoomOut2d' | 'resetScale2d' | 'hoverClosestCartesian' | 'hoverCompareCartesian' | 'zoom3d' | 'pan3d' | 'orbitRotation' | 'tableRotation' | 'resetCameraDefault3d' | 'resetCameraLastSave3d' | 'hoverClosest3d' | 'zoomInGeo' | 'zoomOutGeo' | 'resetGeo' | 'hoverClosestGeo' | 'hoverClosestGl2d' | 'hoverClosestPie' | 'toggleHover' | 'toImage' | 'resetViews' | 'toggleSpikelines' */>
    var modeBarButtonsToAdd: dynamic /* Array<dynamic /* 'lasso2d' | 'select2d' | 'sendDataToCloud' | 'autoScale2d' | 'zoom2d' | 'pan2d' | 'zoomIn2d' | 'zoomOut2d' | 'resetScale2d' | 'hoverClosestCartesian' | 'hoverCompareCartesian' | 'zoom3d' | 'pan3d' | 'orbitRotation' | 'tableRotation' | 'resetCameraDefault3d' | 'resetCameraLastSave3d' | 'hoverClosest3d' | 'zoomInGeo' | 'zoomOutGeo' | 'resetGeo' | 'hoverClosestGeo' | 'hoverClosestGl2d' | 'hoverClosestPie' | 'toggleHover' | 'toImage' | 'resetViews' | 'toggleSpikelines' */> | Array<ModeBarButton> */
    var modeBarButtons: dynamic /* Array<Array<dynamic /* 'lasso2d' | 'select2d' | 'sendDataToCloud' | 'autoScale2d' | 'zoom2d' | 'pan2d' | 'zoomIn2d' | 'zoomOut2d' | 'resetScale2d' | 'hoverClosestCartesian' | 'hoverCompareCartesian' | 'zoom3d' | 'pan3d' | 'orbitRotation' | 'tableRotation' | 'resetCameraDefault3d' | 'resetCameraLastSave3d' | 'hoverClosest3d' | 'zoomInGeo' | 'zoomOutGeo' | 'resetGeo' | 'hoverClosestGeo' | 'hoverClosestGl2d' | 'hoverClosestPie' | 'toggleHover' | 'toImage' | 'resetViews' | 'toggleSpikelines' */>> | Array<Array<ModeBarButton>> | false */
    var displaylogo: Boolean
    var plotGlPixelRatio: Number
    var setBackground: () -> dynamic
    var topojsonURL: String
    var mapboxAccessToken: String
    var logging: dynamic /* Boolean | 0 | 1 | 2 */
    var globalTransforms: Array<Any>
    var locale: String
    var responsive: Boolean
}

external interface RangeSlider {
    var visible: Boolean
    var thickness: Number
    var range: dynamic /* JsTuple<dynamic, dynamic> */
    var borderwidth: Number
    var bordercolor: String
    var bgcolor: String
}

external interface RangeSelectorButton {
    var step: dynamic /* 'second' | 'minute' | 'hour' | 'day' | 'month' | 'year' | 'all' */
    var stepmode: dynamic /* 'backward' | 'todate' */
    var count: Number
    var label: String
}

external interface RangeSelector : Label {
    var buttons: Array<RangeSelectorButton>
    var visible: Boolean
    var x: Number
    var xanchor: dynamic /* 'auto' | 'left' | 'center' | 'right' */
    var y: Number
    var yanchor: dynamic /* 'auto' | 'top' | 'middle' | 'bottom' */
    var activecolor: String
    var borderwidth: Number
}

external interface Camera {
    var up: Point
    var center: Point
    var eye: Point
}

external interface Label {
    var bgcolor: String
    var bordercolor: String
    var font: Font
}

external interface HoverLabel : Label {
    var align: dynamic /* "left" | "right" | "auto" */
    var namelength: Number
}

external interface Annotations : Label {
    var visible: Boolean
    var text: String
    var textangle: String
    var width: Number
    var height: Number
    var opacity: Number
    var align: dynamic /* 'left' | 'center' | 'right' */
    var valign: dynamic /* 'top' | 'middle' | 'bottom' */
    var borderpad: Number
    var borderwidth: Number
    var showarrow: Boolean
    var arrowcolor: String
    var arrowhead: Number
    var startarrowhead: Number
    var arrowside: dynamic /* 'end' | 'start' */
    var arrowsize: Number
    var startarrowsize: Number
    var arrowwidth: Number
    var standoff: Number
    var startstandoff: Number
    var ax: Number
    var ay: Number
    var axref: String /* 'pixel' */
    var ayref: String /* 'pixel' */
    var xref: dynamic /* 'paper' | 'x' */
    var x: dynamic /* Number | String */
    var xanchor: dynamic /* 'auto' | 'left' | 'center' | 'right' */
    var xshift: Number
    var yref: dynamic /* 'paper' | 'y' */
    var y: dynamic /* Number | String */
    var yanchor: dynamic /* 'auto' | 'top' | 'middle' | 'bottom' */
    var yshift: Number
    var clicktoshow: dynamic /* false | 'onoff' | 'onout' */
    var xclick: Any
    var yclick: Any
    var hovertext: String
    var hoverlabel: HoverLabel
    var captureevents: Boolean
}

external interface Image {
    var visible: Boolean
    var source: String
    var layer: dynamic /* 'above' | 'below' */
    var sizex: Number
    var sizey: Number
    var sizing: dynamic /* 'fill' | 'contain' | 'stretch' */
    var opacity: Number
    var x: dynamic /* Number | String */
    var y: dynamic /* Number | String */
    var xanchor: dynamic /* 'left' | 'center' | 'right' */
    var yanchor: dynamic /* 'top' | 'middle' | 'bottom' */
    var xref: dynamic /* 'paper' | 'x' */
    var yref: dynamic /* 'paper' | 'y' */
}

external interface Scene {
    var bgcolor: String
    var camera: Camera
    var domain: Domain
    var aspectmode: dynamic /* 'auto' | 'cube' | 'data' | 'manual' */
    var aspectratio: Point
    var xaxis: SceneAxis
    var yaxis: SceneAxis
    var zaxis: SceneAxis
    var dragmode: dynamic /* 'orbit' | 'turntable' | 'zoom' | 'pan' | false */
    var hovermode: dynamic /* 'closest' | false */
    var annotations: dynamic /* Annotations | Array<Annotations> */
    var captureevents: Boolean
}

external interface Domain {
    var x: Array<Number>
    var y: Array<Number>
}

external interface Frame {
    var group: String
    var name: String
    var traces: Array<Number>
    var baseframe: String
    var data: Array<Data>
    var layout: Layout
}

external interface Transition {
    var duration: Number
    var easing: dynamic /* 'linear' | 'quad' | 'cubic' | 'sin' | 'exp' | 'circle' | 'elastic' | 'back' | 'bounce' | 'linear-in' | 'quad-in' | 'cubic-in' | 'sin-in' | 'exp-in' | 'circle-in' | 'elastic-in' | 'back-in' | 'bounce-in' | 'linear-out' | 'quad-out' | 'cubic-out' | 'sin-out' | 'exp-out' | 'circle-out' | 'elastic-out' | 'back-out' | 'bounce-out' | 'linear-in-out' | 'quad-in-out' | 'cubic-in-out' | 'sin-in-out' | 'exp-in-out' | 'circle-in-out' | 'elastic-in-out' | 'back-in-out' | 'bounce-in-out' */
}

external interface SliderStep {
    var visible: Boolean
    var method: dynamic /* 'animate' | 'relayout' | 'restyle' | 'skip' | 'update' */
    var args: Array<Any>
    var label: String
    var value: String
    var execute: Boolean
}

external interface Padding {
    var t: Number
    var r: Number
    var b: Number
    var l: Number
    var editType: String /* 'arraydraw' */
}

external interface `T$7` {
    var visible: Boolean
    var xanchor: dynamic /* 'left' | 'center' | 'right' */
    var offset: Number
    var prefix: String
    var suffix: String
    var font: Font
}

external interface Slider {
    var visible: Boolean
    var active: Number
    var steps: Array<SliderStep>
    var lenmode: dynamic /* 'fraction' | 'pixels' */
    var len: Number
    var x: Number
    var y: Number
    var pad: Padding
    var xanchor: dynamic /* 'auto' | 'left' | 'center' | 'right' */
    var yanchor: dynamic /* 'auto' | 'top' | 'middle' | 'bottom' */
    var transition: Transition
    var currentvalue: `T$7`
    var font: Font
    var activebgcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var bgcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var bordercolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var borderwidth: Number
    var ticklen: Number
    var tickcolor: dynamic /* String | Number | Array<dynamic /* String | Number | Nothing? | Nothing? */> | Array<Array<dynamic /* String | Number | Nothing? | Nothing? */>> */
    var tickwidth: Number
    var minorticklen: Number
}
