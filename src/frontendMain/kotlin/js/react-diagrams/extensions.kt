package js.reactdiagrams

import js.react.React
import kotlinext.js.asJsObject


fun ReactCanvasCore.CanvasWidget.Companion.create(props: ReactCanvasCore.CanvasWidget.Props): React.Element =
	React.createElement(ReactCanvasCore.CanvasWidget::class.js, props.asJsObject())
