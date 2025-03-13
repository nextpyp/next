
import * as THREE                     from 'three'
import   WEBGL                        from 'three/examples/jsm/capabilities/WebGL'
import { OrbitControls }              from 'three/examples/jsm/controls/OrbitControls'
import { VolumeRenderShader1 }        from 'three/examples/jsm/shaders/VolumeShader'
import { GUI, GUIController }         from 'dat.gui' /* So that we can use JSDoc with out a warning about importing GUIController --> */ // eslint-disable-line

import { readMrc }                    from './mrc'
import { hexToRgb }                   from './three_help'
import * as DEFAULT                   from './three_defaults'
import {fragmentShader, vertexShader, fragmentShader2} from './three_shaders'


const CM_TEXTURES = {
	viridis: new THREE.TextureLoader().load('/textures/cm_viridis.png'),
	gray: new THREE.TextureLoader().load('/textures/cm_gray.png')
};


function makeIsoTextureFromFloatData(rImage, lX, lY, lZ) {

	const imageData = new Uint8Array(rImage.map(v => Math.round(256 * v))) // Scale values up from (0, 1) to (0, 256)

	// Texture to hold the volume. We have scalars, so we put our data in the red channel.
	const isoTexture = new THREE.Data3DTexture(imageData, lX, lY, lZ);
	isoTexture.format = THREE.RedFormat;
	isoTexture.minFilter = isoTexture.magFilter = THREE.LinearFilter;
	isoTexture.unpackAlignment = 1;
	isoTexture.needsUpdate = true;

	return isoTexture;
}


function createIsoMesh(isoTexture) {

	const isoGeometry = new THREE.BoxGeometry(1, 1, 1);
	const isoMaterial = new THREE.RawShaderMaterial({
		glslVersion: THREE.GLSL3,
		uniforms: {
			map: { value: isoTexture },
			cameraPos: { value: new THREE.Vector3() },
			threshold: { value: DEFAULT.ISOTHRESHOLD },
			steps: { value: DEFAULT.STEPS },
			redValue: { value: DEFAULT.FIRST_COLOR_VALUES.r },
			greenValue: { value: DEFAULT.FIRST_COLOR_VALUES.g },
			blueValue: { value: DEFAULT.FIRST_COLOR_VALUES.b }
		},
		vertexShader,
		fragmentShader,
		side: THREE.BackSide,
	});

	return new THREE.Mesh(isoGeometry, isoMaterial);
}


function createEmptyDoubleIsoMesh() {

	const isoGeometry = new THREE.BoxGeometry(1, 1, 1);
	const isoMaterial = new THREE.RawShaderMaterial({
		glslVersion: THREE.GLSL3,
		uniforms: {
			cameraPos: { value: new THREE.Vector3() },
			threshold: { value: DEFAULT.ISOTHRESHOLD },
			steps: { value: DEFAULT.STEPS },
			map1: { value: null },
			map2: { value: null },
			redValue1: { value: DEFAULT.FIRST_COLOR_VALUES.r },
			greenValue1: { value: DEFAULT.FIRST_COLOR_VALUES.g },
			blueValue1: { value: DEFAULT.FIRST_COLOR_VALUES.b },
			redValue2: { value: DEFAULT.SECOND_COLOR_VALUES.r },
			greenValue2: { value: DEFAULT.SECOND_COLOR_VALUES.g },
			blueValue2: { value: DEFAULT.SECOND_COLOR_VALUES.b }
		},
		vertexShader,
		fragmentShader: fragmentShader2,
		side: THREE.BackSide,
	});

	return new THREE.Mesh(isoGeometry, isoMaterial);
}


function createMipMesh(rImage, lX, lY, lZ, volconfig) {

	const shader = VolumeRenderShader1;
	const uniforms = THREE.UniformsUtils.clone(shader.uniforms);

	const mipTexture = new THREE.Data3DTexture(rImage, lX, lY, lZ);
	mipTexture.format = THREE.RedFormat;
	mipTexture.type = THREE.FloatType;
	mipTexture.minFilter = THREE.LinearFilter;
	mipTexture.magFilter = THREE.LinearFilter;
	mipTexture.unpackAlignment = 1;
	mipTexture.needsUpdate = true;

	uniforms["u_data"].value = mipTexture;
	uniforms["u_size"].value.set(lX, lY, lZ);
	uniforms["u_clim"].value.set(volconfig.clim1, volconfig.clim2);
	uniforms["u_renderstyle"].value = 0; // 0: MIP, 1: ISO
	uniforms["u_renderthreshold"].value = volconfig.isothreshold; // For ISO renderstyle
	uniforms["u_cmdata"].value = CM_TEXTURES[volconfig.colormap];

	const mipMaterial = new THREE.ShaderMaterial({
		uniforms: uniforms,
		vertexShader: shader.vertexShader,
		fragmentShader: shader.fragmentShader,
		side: THREE.BackSide
	});

	const mipGeometry = new THREE.BoxGeometry(lX, lY, lZ);
	mipGeometry.translate(lX / 2 - 0.5, lY / 2 - 0.5, lZ / 2 - 0.5);

	const mipMesh = new THREE.Mesh(mipGeometry, mipMaterial);
	mipMesh.scale.x *= 1 / lX;
	mipMesh.scale.y *= 1 / lY;
	mipMesh.scale.z *= 1 / lZ;
	mipMesh.translateOnAxis(new THREE.Vector3(-1, -1, -1), 0.5);

	return mipMesh;
}


/**
 * @param {ArrayBuffer} data
 * @returns {[Float32Array, number, number, number]}
 */
function processArrayBufferResponse(data) {

	let { image, headerdict } = readMrc(new Uint8Array(data));

	// convert the image data to Float32 format if needed
	if (!(image instanceof Float32Array)) {
		const floatImage = new Float32Array(image.length);
		for (let i=0; i<image.length; i++) {
			floatImage[i] = image[i];
		}
		image = floatImage;
	}

	// normalize to the range [0,1]
	let max = image[0];
	let min = image[0];
	for (let i=0; i<image.length; i++) {
		if (image[i] > max) {
			max = image[i];
		}
		if (image[i] < min) {
			min = image[i];
		}
	}
	const f = max - min;
	for (let i=0; i<image.length; i++) {
		image[i] = (image[i] - min)/f;
	}

	return [image, headerdict["nx"], headerdict["ny"], headerdict["nz"]];
}



export class Renderer {

	#globalRenderer1;
	#globalRenderer2;
	#globalHostDiv1;
	#globalHostDiv2;
	#globalScene1;
	#globalScene2;

	#updateUniformsFunction = () => {};
	#updateSecondCamera = () => {};

	#render1Needed = true;
	#render2Needed = true;

	/** @type {THREE.PerspectiveCamera|undefined} */
	#globalCamera1 = undefined;

	/** @type {THREE.PerspectiveCamera|undefined} */
	#globalCamera2 = undefined;

	#firstMeshShowing  = false;
	#secondMeshShowing = false;
	#usingDoubleViews  = true;
	#animating = true;

	/** @type {THREE.Mesh} */ #isoMesh1;
	/** @type {THREE.Mesh} */ #mipMesh1;
	/** @type {THREE.Mesh} */ #isoMesh2;
	/** @type {THREE.Mesh} */ #mipMesh2;
	/** @type {THREE.Mesh} */ #combinedIsoMesh;

	#isoTexture1;
	#isoTexture2;

	#volconfig = {
		clim1:        DEFAULT.CLIM[0],
		clim2:        DEFAULT.CLIM[1],
		renderstyle:  DEFAULT.RENDERSTYLE,
		isothreshold: DEFAULT.ISOTHRESHOLD,
		colormap:     DEFAULT.COLORMAP,
		sharpness:    DEFAULT.SHARPNESS,
		firstColor:   DEFAULT.FIRST_COLOR,
		secondColor:  DEFAULT.SECOND_COLOR,
		steps:        DEFAULT.STEPS,

		reset: () => {
			this.#volconfig.clim1 =        DEFAULT.CLIM[0];
			this.#volconfig.clim2 =        DEFAULT.CLIM[1];
			this.#volconfig.renderstyle =  DEFAULT.RENDERSTYLE;
			this.#volconfig.isothreshold = DEFAULT.ISOTHRESHOLD;
			this.#volconfig.colormap =     DEFAULT.COLORMAP;
			this.#volconfig.sharpness =    DEFAULT.SHARPNESS;
			this.#volconfig.firstColor =   DEFAULT.FIRST_COLOR;
			this.#volconfig.secondColor =  DEFAULT.SECOND_COLOR;
			this.#volconfig.steps =        DEFAULT.STEPS;
			this.#updateUniformsFunction();
			this.#updateRenderer();
		}
	};


	checkFirstMeshIsShowing()  {
		return this.#firstMeshShowing;
	}
	checkSecondMeshIsShowing() {
		return this.#secondMeshShowing;
	}
	checkUsingDoubleViews() {
		return this.#usingDoubleViews;
	}

	clearMesh1() {

		this.#globalScene1.remove(this.#isoMesh1);
		this.#globalScene1.remove(this.#mipMesh1);
		if (this.#usingDoubleViews || !this.#secondMeshShowing) {
			this.#globalScene1.remove(this.#combinedIsoMesh);
		}

		if (this.#usingDoubleViews) {
			this.#globalHostDiv1.style.display = "none";
		}

		this.#combinedIsoMesh.material.uniforms.map1.value = null;

		this.#firstMeshShowing = false;
		this.#updateUniformsFunction();
		this.#render1Needed = true;
	}

	#disposeMesh1() {
		if (this.#isoTexture1) {
			this.#isoTexture1.dispose();
			this.#isoTexture1 = null;
		}
		if (this.#isoMesh1) {
			this.#isoMesh1.geometry.dispose();
			this.#isoMesh1.material.dispose();
			this.#isoMesh1 = null;
		}
		if (this.#mipMesh1) {
			this.#mipMesh1.geometry.dispose();
			this.#mipMesh1.material.uniforms.u_data.value.dispose();
			this.#mipMesh1.material.dispose();
			this.#mipMesh1 = null;
		}
	}

	createMesh1fromData(data) {

		if (this.#firstMeshShowing) {
			this.clearMesh1();
		}

		this.#disposeMesh1();

		const imageData = processArrayBufferResponse(data);
		this.#isoTexture1 = makeIsoTextureFromFloatData(...imageData);
		this.#isoMesh1 = createIsoMesh(this.#isoTexture1);
		this.#mipMesh1 = createMipMesh(...imageData, this.#volconfig);

		this.#refreshMesh1Data();
		this.#render1Needed = true;
	}

	#refreshMesh1Data() {

		if (this.#usingDoubleViews) {
			this.#globalScene1.add(this.#isoMesh1);
			this.#globalScene1.add(this.#mipMesh1);
		} else {
			// Note, it is possible that this code will be reached even
			// when combinedIsoMesh is already part of the scene. However,
			// this is not an issue, and the object is not added twice.
			// (verified by examining Object3D.add in Three.JS source code)
			this.#globalScene1.add(this.#combinedIsoMesh);
		}

		this.#globalHostDiv1.style.display = "inline-block";

		this.#combinedIsoMesh.material.uniforms.map1.value = this.#isoTexture1;

		this.#firstMeshShowing = true;
		this.#updateUniformsFunction();
		this.#updateRenderer();
	}

	clearMesh2() {

		this.#globalHostDiv2.style.display = "none";

		this.#globalScene2.remove(this.#isoMesh2);
		this.#globalScene2.remove(this.#mipMesh2);
		if (!this.#firstMeshShowing) {
			this.#globalScene1.remove(this.#combinedIsoMesh);
		}

		if (!this.#firstMeshShowing) {
			this.#globalHostDiv1.style.display = "none";
		}

		this.#combinedIsoMesh.material.uniforms.map2.value = null;

		this.#secondMeshShowing = false;
		this.#updateUniformsFunction();
		this.#render1Needed = true;
		this.#render2Needed = true;
	}


	#disposeMesh2() {
		if (this.#isoTexture2) {
			this.#isoTexture2.dispose();
			this.#isoTexture2 = null;
		}
		if (this.#isoMesh2) {
			this.#isoMesh2.geometry.dispose();
			this.#isoMesh2.material.dispose();
			this.#isoMesh2 = null;
		}
		if (this.#mipMesh2) {
			this.#mipMesh2.geometry.dispose();
			this.#mipMesh2.material.uniforms.u_data.value.dispose();
			this.#mipMesh2.material.dispose();
			this.#mipMesh2 = null;
		}
	}

	createMesh2fromData(data) {

		if (this.#secondMeshShowing) {
			this.clearMesh2();
		}

		this.#disposeMesh2();

		const imageData = processArrayBufferResponse(data);
		this.#isoTexture2 = makeIsoTextureFromFloatData(...imageData);
		this.#isoMesh2 = createIsoMesh(this.#isoTexture2);
		this.#mipMesh2 = createMipMesh(...imageData, this.#volconfig);

		this.#refreshMesh2Data();
		this.#render1Needed = true;
		this.#render2Needed = true;
	}

	#refreshMesh2Data() {

		if (this.#usingDoubleViews) {
			this.#globalScene2.add(this.#isoMesh2);
			this.#globalScene2.add(this.#mipMesh2);
			this.#globalHostDiv2.style.display = "inline-block";
		} else {
			// Note, it is possible that this code will be reached even
			// when combinedIsoMesh is already part of the scene. However,
			// this is not an issue, and the object is not added twice.
			// (verified by examining Object3D.add in Three.JS source code)
			this.#globalScene1.add(this.#combinedIsoMesh);
		}

		if (!this.#usingDoubleViews || this.#firstMeshShowing) {
			this.#globalHostDiv1.style.display = "inline-block";
		}

		this.#combinedIsoMesh.material.uniforms.map2.value = this.#isoTexture2;

		this.#secondMeshShowing = true;
		this.#updateUniformsFunction();
		this.#updateRenderer();
	}

	#moveCamera1(event) {
		// Called when camera 2 moves
		this.#globalCamera1.position.copy(this.#globalCamera2.position);
		this.#globalCamera1.rotation.copy(this.#globalCamera2.rotation);
	}

	#moveCamera2(event) {
		// Called when camera 1 moves
		if (this.#globalCamera2 && this.#usingDoubleViews) {
			this.#globalCamera2.position.copy(this.#globalCamera1.position);
			this.#globalCamera2.rotation.copy(this.#globalCamera1.rotation);
		}
	}

	setUseDoubleViews(useDoubleViews) {
		if (this.#usingDoubleViews !== useDoubleViews) {
			this.#usingDoubleViews = useDoubleViews;
			if (this.#firstMeshShowing) {
				this.clearMesh1();
				this.#refreshMesh1Data();
			}
			if (this.#secondMeshShowing) {
				this.clearMesh2();
				this.#refreshMesh2Data();
			}
			if (this.#usingDoubleViews === true) {
				this.#moveCamera2();
			}
		}
	}

	/**
	 * @param {HTMLElement} hostElement
	 * @param {HTMLElement} controlsHost
	 * @param {number} size
	 */
	init(hostElement, controlsHost, size) {

		// don't init more than once, or we risk leaking resources
		if (this.#combinedIsoMesh != null) {
			return;
		}

		this.#combinedIsoMesh = createEmptyDoubleIsoMesh();
		this.#setup3dView(hostElement, controlsHost, size);
		this.#setup3dView(hostElement, controlsHost, size, false);
	}

	dispose() {

		this.#animating = false;

		// https://threejs.org/docs/index.html#manual/en/introduction/How-to-dispose-of-objects

		if (this.#combinedIsoMesh != null) {
			this.#combinedIsoMesh.geometry.dispose();
			this.#combinedIsoMesh.material.dispose();
			this.#combinedIsoMesh = null;
		}

		this.#disposeMesh1();
		this.#disposeMesh2();

		if (this.#globalRenderer1 != null) {
			this.#globalRenderer1.dispose();
			this.#globalRenderer1 = null;
		}
		if (this.#globalRenderer2 != null) {
			this.#globalRenderer2.dispose();
			this.#globalRenderer2 = null;
		}

		// TODO: what other cleanup can we do here??
		//	   the original code had almost no cleanup at all =(
	}

	/**
	 * @param {HTMLElement} hostElement
	 * @param {HTMLElement} controlsHost
	 * @param {number} size
	 * @param {boolean} [isMainView]
	 */
	#setup3dView(hostElement, controlsHost, size, isMainView = true) {

		const components = this.#create3dView(hostElement, controlsHost, size, isMainView);
		if (components === undefined) {
			return;
		}

		if (isMainView) {
			this.#globalCamera1 = components.camera;
			this.#globalScene1 = components.scene;
			this.#globalRenderer1 = components.renderer;
			this.#globalHostDiv1 = components.hostDiv;
		} else {
			this.#globalCamera2 = components.camera;
			this.#globalScene2 = components.scene;
			this.#globalRenderer2 = components.renderer;
			this.#globalHostDiv2 = components.hostDiv;
			this.#globalHostDiv2.style.display = "none";
		}
	}

	/**
	 * Creates the 3D view using `three.js`.
	 *
	 * @param {HTMLElement} hostElement
	 * @param {HTMLElement} controlsHost
	 * @param {number} size
	 * @param {boolean} isMainView
	 * @returns {{
	 *   hostDiv: HTMLElement,
	 *   camera: THREE.PerspectiveCamera,
	 *   renderer: THREE.WebGLRenderer,
	 *   scene: THREE.Scene
	 * }}
	 */
	#create3dView(hostElement, controlsHost, size, isMainView) {

		try {

			if (WEBGL.isWebGL2Available() === false) {
				hostElement.appendChild(WEBGL.getWebGL2ErrorMessage());
			}

			// CREATE THE HOST ELEMENT

			const hostDiv = document.createElement('div');
			hostDiv.style.position = "relative";
			hostDiv.style.width = (isMainView ? size : 0) + "px"; // Pretend the second view has no width so that it always displays properly
			hostDiv.style.display = "none";
			hostElement.appendChild(hostDiv);

			// CREATE CAMERA, SCENE, AND RENDERER

			const aspect = 1;
			const camera = new THREE.PerspectiveCamera(30, aspect, 0.1, 1000);
			const orthoCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100);
			camera.position.set(...DEFAULT.CAMERA_POSITION);
			camera.up.set(0, 0, 1);
			orthoCamera.position.set(...DEFAULT.CAMERA_POSITION);
			orthoCamera.up.set(0, 0, 1); // In our data, z is up
			camera.zoom = 0.8;
			camera.updateProjectionMatrix();
			orthoCamera.updateProjectionMatrix();

			const defaultCamera = DEFAULT.RENDERSTYLE === "iso" ? camera : orthoCamera;
			let currentCamera1;
			let currentCamera2;
			if (isMainView) {
				currentCamera1 = defaultCamera;
			} else {
				currentCamera2 = defaultCamera;
			}

			const scene = new THREE.Scene();
			const renderer = new THREE.WebGLRenderer({ alpha: true }); // Use alpha parameter to enable transparency
			renderer.setClearColor(0x000000, 0); // second param is opacity, 0 => transparent
			renderer.setPixelRatio(0.25 * window.devicePixelRatio);
			renderer.setSize(size, size)
			hostDiv.appendChild(renderer.domElement);

			const controls = new OrbitControls(camera, renderer.domElement); // The controls always move the perspective camera
			controls.minZoom = 0.25;
			controls.maxZoom = 8;
			controls.enablePan = false;
			controls.update();
			controls.addEventListener("change", () => {
				this.#render1Needed = true;
				this.#render2Needed = true;
				isMainView ? this.#moveCamera2() : this.#moveCamera1();
			})

			const animate = () => {

				if (!this.#animating) {
					return;
				}

				requestAnimationFrame(animate);

				if (isMainView ? this.#render1Needed : this.#render2Needed) {

					if (this.#firstMeshShowing && this.#usingDoubleViews) {
						this.#isoMesh1.material.uniforms.cameraPos.value.copy(camera.position);
					}
					if (this.#secondMeshShowing && this.#usingDoubleViews) {
						this.#isoMesh2.material.uniforms.cameraPos.value.copy(camera.position);
					}
					if ((this.#firstMeshShowing || this.#secondMeshShowing) && !this.#usingDoubleViews) {
						this.#combinedIsoMesh.material.uniforms.cameraPos.value.copy(camera.position);
					}

					// Adjust position of orthographic camera
					orthoCamera.position.copy(camera.position);
					orthoCamera.position.multiplyScalar(DEFAULT.CAMERA_DISTANCE / orthoCamera.position.length());
					orthoCamera.rotation.copy(camera.rotation);
					orthoCamera.zoom = 2 * DEFAULT.CAMERA_DISTANCE / camera.position.length();
					orthoCamera.updateProjectionMatrix();
					if (isMainView) {
						renderer.render(scene, currentCamera1);
						this.#render1Needed = false;
					} else {
						renderer.render(scene, currentCamera2);
						this.#render2Needed = false;
					}
				}
			}
			animate();

			let updateGuiOptions = () => {};

			if (isMainView) {

				// Add GUI
				const gui = new GUI({ autoPlace: false });
				gui.remember(this.#volconfig);
				gui.useLocalStorage = true;

				controlsHost.appendChild(gui.domElement);

				const updateUniformsAndSave = () => {
					updateUniforms();
					gui.saveToLocalStorageIfPossible();
				};

				const isoCtrl = gui.add(this.#volconfig, 'isothreshold', 0, 1, 0.01)
					.name("Contour value")
					.listen()
					.onChange(updateUniformsAndSave);
				gui.add(this.#volconfig, 'sharpness', 0.02, 1, 0.01)
					.name("Level of detail")
					.listen()
					.onChange(() => {
						this.#updateRenderer();
						updateUniformsAndSave();
					});
				const folder = gui.addFolder('Advanced options');
				const stepsCtrl = folder.add(this.#volconfig, 'steps', 100, 5000, 1)
					.name("Sharpness")
					.listen()
					.onChange(updateUniformsAndSave);
				const clim1Ctrl = folder.add(this.#volconfig, 'clim1', 0, 1, 0.01)
					.name("Brightness")
					.listen()
					.onChange(updateUniformsAndSave);
				const clim2Ctrl = folder.add(this.#volconfig, 'clim2', 0, 1, 0.01)
					.name("Contrast")
					.listen()
					.onChange(updateUniformsAndSave);
				const colormapCtrl = folder.add(this.#volconfig, 'colormap', { viridis: 'viridis', gray: 'gray'})
					.name("Colormap")
					.listen()
					.onChange(updateUniformsAndSave);
				const renderCtrl = folder.add(this.#volconfig, 'renderstyle', { mip: 'mip', iso: 'iso' })
					.name("Render style")
					.listen()
					.onChange(updateUniformsAndSave);
				const firstColorCtrl = folder.addColor(this.#volconfig, 'firstColor')
					.name("Map 1 color")
					.listen()
					.onChange(updateUniformsAndSave);
				const secondColorCtrl = folder.addColor(this.#volconfig, 'secondColor')
					.name("Map 2 color")
					.listen()
					.onChange(updateUniformsAndSave);
				const resetButton = gui.add(this.#volconfig, 'reset')
					.name("Reset to default parameters");

				if (DEFAULT.CLOSE_GUI) {
					folder.close();
				}
				if (DEFAULT.CLOSE_GUI) {
					gui.close();
				}

				/**
				 * @param {GUIController} controller
				 */
				function changeStatusOf(controller, disable, reason="") {
					const element = controller.domElement;
					element.style.opacity = disable ? 0.5 : 1;
					element.style.filter = disable ? "grayscale(100%)" : "none";
					element.style.pointerEvents = disable ? "none" : "auto";
					element.parentElement.title = reason;
				}

				const disable = (control, param, req) => {
					changeStatusOf(control, true, `Only available when ${param} is set to ${req}`);
				};
				const enable = (control) => changeStatusOf(control, false);

				updateGuiOptions = () => {

					if (!this.#usingDoubleViews) {
						this.#volconfig.renderstyle = "iso";
						renderCtrl.domElement.value = "iso";
						changeStatusOf(renderCtrl, true, `When using overlay view, only iso renderstyle is available.`)
					} else {
						enable(renderCtrl);
					}

					if (this.#volconfig.renderstyle === "mip") {
						disable(firstColorCtrl, "renderstyle", "iso");
						disable(secondColorCtrl, "renderstyle", "iso");
						disable(isoCtrl, "renderstyle", "iso");
						disable(stepsCtrl, "renderstyle", "iso");
						enable(colormapCtrl);
						enable(clim1Ctrl);
						enable(clim2Ctrl);
					} else {
						enable(isoCtrl);
						enable(firstColorCtrl);
						enable(secondColorCtrl);
						enable(stepsCtrl);
						disable(colormapCtrl, "renderstyle", "mip");
						disable(clim1Ctrl, "renderstyle", "mip");
						disable(clim2Ctrl, "renderstyle", "mip");
					}

					const paramsAreDefault =
						this.#volconfig.clim1         === DEFAULT.CLIM[0]      &&
						this.#volconfig.clim2         === DEFAULT.CLIM[1]      &&
						this.#volconfig.renderstyle   === DEFAULT.RENDERSTYLE  &&
						this.#volconfig.isothreshold  === DEFAULT.ISOTHRESHOLD &&
						this.#volconfig.colormap      === DEFAULT.COLORMAP     &&
						this.#volconfig.sharpness     === DEFAULT.SHARPNESS    &&
						this.#volconfig.firstColor    === DEFAULT.FIRST_COLOR  &&
						this.#volconfig.secondColor   === DEFAULT.SECOND_COLOR &&
						this.#volconfig.steps         === DEFAULT.STEPS;

					if (paramsAreDefault) {
						changeStatusOf(resetButton, true, "Parameters are already set to their default values.");
						resetButton.domElement.parentElement.childNodes[0].style.color = "#aaa";
						resetButton.domElement.parentElement.parentElement.classList.add("disabled");
					} else {
						enable(resetButton);
						resetButton.domElement.parentElement.childNodes[0].style.color = "#000";
						resetButton.domElement.parentElement.parentElement.classList.remove("disabled");
					}

				}
				updateGuiOptions();

			} else {

				this.#updateSecondCamera = () => {
					if (this.#volconfig.renderstyle === "mip") {
						currentCamera2 = orthoCamera;
					} else {
						currentCamera2 = camera;
					}
				}
			}

			const updateUniforms = () => {

				if (this.#firstMeshShowing) {

					this.#isoMesh1.material.uniforms.threshold.value = this.#volconfig.isothreshold; // For ISO renderstyle

					const color = hexToRgb(this.#volconfig.firstColor);

					this.#isoMesh1.material.uniforms.steps.value = this.#volconfig.steps;
					this.#isoMesh1.material.uniforms.redValue.value = color.r;
					this.#isoMesh1.material.uniforms.greenValue.value = color.g;
					this.#isoMesh1.material.uniforms.blueValue.value = color.b;

					this.#combinedIsoMesh.material.uniforms.threshold.value = this.#volconfig.isothreshold; // For ISO renderstyle
					this.#combinedIsoMesh.material.uniforms.steps.value = this.#volconfig.steps;
					this.#combinedIsoMesh.material.uniforms.redValue1.value = color.r;
					this.#combinedIsoMesh.material.uniforms.greenValue1.value = color.g;
					this.#combinedIsoMesh.material.uniforms.blueValue1.value = color.b;

					this.#mipMesh1.material.uniforms["u_cmdata"].value = CM_TEXTURES[this.#volconfig.colormap];
					this.#mipMesh1.material.uniforms["u_clim"].value.set(this.#volconfig.clim1, this.#volconfig.clim2);
				}

				if (this.#secondMeshShowing) {

					this.#isoMesh2.material.uniforms.threshold.value = this.#volconfig.isothreshold; // For ISO renderstyle

					const color = hexToRgb(this.#volconfig.secondColor);

					this.#isoMesh2.material.uniforms.steps.value = this.#volconfig.steps;
					this.#isoMesh2.material.uniforms.redValue.value = color.r;
					this.#isoMesh2.material.uniforms.greenValue.value = color.g;
					this.#isoMesh2.material.uniforms.blueValue.value = color.b;

					this.#combinedIsoMesh.material.uniforms.steps.value = this.#volconfig.steps;
					this.#combinedIsoMesh.material.uniforms.redValue2.value = color.r;
					this.#combinedIsoMesh.material.uniforms.greenValue2.value = color.g;
					this.#combinedIsoMesh.material.uniforms.blueValue2.value = color.b;

					this.#mipMesh2.material.uniforms["u_cmdata"].value = CM_TEXTURES[this.#volconfig.colormap];
					this.#mipMesh2.material.uniforms["u_clim"].value.set(this.#volconfig.clim1, this.#volconfig.clim2);
				}

				// Disable irrelevant options, switch to correct camera
				updateGuiOptions();
				this.#updateSecondCamera();
				if (this.#volconfig.renderstyle === "mip") {
					currentCamera1 = orthoCamera;
					if (this.#firstMeshShowing) {
						this.#mipMesh1.visible = true;
						this.#isoMesh1.visible = false;
					}
					if (this.#secondMeshShowing) {
						this.#mipMesh2.visible = true;
						this.#isoMesh2.visible = false;
					}
				} else {
					currentCamera1 = camera;
					if (this.#firstMeshShowing) {
						this.#mipMesh1.visible = false;
						this.#isoMesh1.visible = true;
					}
					if (this.#secondMeshShowing) {
						this.#mipMesh2.visible = false;
						this.#isoMesh2.visible = true;
					}
				}

				this.#render1Needed = true;
				this.#render2Needed = true;
			}

			if (isMainView) {
				this.#updateUniformsFunction = updateUniforms;
			}

			return {
				hostDiv,
				camera,
				renderer,
				scene
			};

		} catch (e) {
			console.error(e);
		}
	}

	#updateRenderer() {

		this.#globalRenderer1.setPixelRatio(this.#volconfig.sharpness * window.devicePixelRatio);
		this.#globalRenderer2.setPixelRatio(this.#volconfig.sharpness * window.devicePixelRatio);

		this.#render1Needed = true;
		this.#render2Needed = true;
	}

	/**
	 * @param {number} size
	 */
	updateRendererSize(size) {

		this.#globalRenderer1.setSize(size, size);

		const aspect = 1; // hostElement.offsetWidth / hostElement.offsetHeight;
		const frustumHeight = this.#globalCamera1.top - this.#globalCamera1.bottom;

		this.#globalCamera1.left = - frustumHeight * aspect / 2;
		this.#globalCamera1.right = frustumHeight * aspect / 2;
		this.#globalCamera1.updateProjectionMatrix();
		this.#globalHostDiv1.style.width = size + "px";

		this.#globalRenderer2.setSize(size, size);

		this.#globalCamera2.left = - frustumHeight * aspect / 2;
		this.#globalCamera2.right = frustumHeight * aspect / 2;
		this.#globalCamera2.updateProjectionMatrix();
		this.#globalHostDiv2.style.width = size + "px";

		this.#render1Needed = true;
		this.#render2Needed = true;
	}
}


export function getUserAgent() {
	return navigator.userAgent;
}
