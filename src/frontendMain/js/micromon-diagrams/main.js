
import * as React from "react";
import * as _ from 'lodash';
import {
	NodeModel,
	DefaultPortModel,
	PortWidget
} from "@projectstorm/react-diagrams";
import {
	AbstractReactFactory,
	Action,
	InputType
} from "@projectstorm/react-canvas-core";


// just enough glue code to allow our Kotlin code to use the ReactDiagrams library
// since the Kotlin/JS compiler can't extend ES2015 classes directly ;_;

export class NodeType {

	constructor(id, name, iconClass) {
		this.id = id;
		this.name = name;
		this.iconClass = iconClass;
	}

	model() {
		return new MicromonNodeModel(this)
	}
}

export function nodeType(id, name, iconClass,) {
	return new NodeType(id, name, iconClass);
}

class MicromonNodeModel extends NodeModel {

	constructor(type) {
		super({
			type: type.id,
			name: type.name
		});
		this.counter = 0;
		this.name = type.name;
		this.type = type;
		this.buttons = [];
		this.content = [];
		this.hasSize = false;
		this.sizeListener = null;
		this.inputsExclusive = true;
		this.onTitleMouseDown = null;
		this.onTitleMouseUp = null;
		this.selected = false;
		// NOTE: isSelected() and setSelected() in the BaseModel ancestor class are used internally
		//   by the diagram and are unrelated to this selected property, which is used by Micromon
	}

	setButtons(... elems) {
		this.buttons = elems;
		this.markDirty();
	}

	setContent(... elems) {
		this.content = elems;
		this.markDirty();
	}

	/**
	 * Values for buttons and content can't be serialized (they're components),
	 * so increment a counter (that can be serialized) to enable reactivity for these properties.
	 */
	markDirty() {
		this.counter += 1;
	}

	addPort(isIn, name, label) {
		let port = new DefaultPortModel({
			in: isIn,
			name: name,
			label: label
		});
		super.addPort(port);
		return port;
	}

	addInPort(name, label) {
		return this.addPort(true, name, label);
	}

	addOutPort(name, label) {
		return this.addPort(false, name, label);
	}

	updateDimensions(dims) {
		super.updateDimensions(dims)
		this.hasSize = true;
		if (this.sizeListener) {
			this.sizeListener()
			this.sizeListener = null;
		}
	}

	onSize(listener) {
		if (this.hasSize) {
			listener();
		} else {
			this.sizeListener = listener;
		}
	}

	// NOTE: need to add our state into the serialize/deserialize methods to get reactivity
	// see: https://github.com/projectstorm/react-diagrams/issues/585

	serialize() {
		return {
			... super.serialize(),
			counter: this.counter,
			name: this.name,
			selected: this.selected
		};
	}

	deserialize(event, engine) {
		super.deserialize(event, engine);
		this.counter = event.data.counter;
		this.name = event.data.name;
		this.selected = event.data.selected;
	}
}


function div(className, ... children) {
	return divEx({ className }, ... children);
}

function divEx(attributes, ... children) {
	return React.createElement("div", attributes, ... children);
}

function span(className, ... children) {
	return spanEx({ className }, ... children);
}

function spanEx(attributes, ... children) {
	return React.createElement("span", attributes, ... children);
}


class MicromonNodeComponent extends React.Component {

	render() {
		let inPorts = _.filter(this.props.node.getPorts(), (port) => port.options.in);
		let outPorts = _.filter(this.props.node.getPorts(), (port) => !port.options.in);
		return div(this.props.node.selected ? "selected" : undefined,
			divEx({
				className: "title",
				onMouseDown: this.props.node.onTitleMouseDown,
				onMouseUp: this.props.node.onTitleMouseUp
			},
				spanEx({
					className: "icon " + this.props.node.type.iconClass,
					title: this.props.node.type.name
				}),
				span("text", this.props.node.name),
				span("buttons", ... this.props.node.buttons)
			),
			div("content", ... this.props.node.content),
			div("ports",
				div("left-ports",
					_.map(inPorts, (port) => {

						// if input ports are exclusive, then only show connected input ports
						let style = undefined;
						if (this.props.node.inputsExclusive && Object.keys(port.getLinks()).length <= 0) {
							style = {
								display: 'none'
							};
						}

						return divEx({
							className: "left-port",
							key: port.getID(),
							style
						},
							React.createElement(PortWidget, {
								className: "in-port",
								engine: this.props.engine,
								port: port
							}),
							div("name", port.options.label)
						);
					})
				),
				div("center-ports"),
				div("right-ports",
					_.map(outPorts, (port) => {
						return divEx({
							className: "right-port",
							key: port.getID()
						},
							div("name", port.options.label),
							React.createElement(PortWidget, {
								className: "out-port",
								engine: this.props.engine,
								port: port
							})
						);
					})
				)
			)
		);
	}
}

export function registerNodeFactory(engine, type) {
	let Factory = class extends AbstractReactFactory {

		constructor() {
			super(type.id);
		}

		generateModel(event) {
			return type.model();
		}

		generateReactWidget(event) {
			return React.createElement(MicromonNodeComponent, { engine: this.engine, node: event.model });
		}
	};
	engine.getNodeFactories().registerFactory(new Factory())
}

export function registerAction(engine, type, callback) {
    let state = engine.getStateMachine().getCurrentState();
    state.registerAction(
    	new Action({
    		type: type,
    		fire: function (event) {
    			let target = engine.getActionEventBus().getModelForEvent(event);
    			callback(target);
    		}
    	})
    )
}
