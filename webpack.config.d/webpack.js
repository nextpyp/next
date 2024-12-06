config.resolve.modules.push("../../processedResources/frontend/main");

if (config.devServer) {
	// NOTE: dev server isn't used in container-land
    config.devServer.hot = true;
    config.devtool = 'eval-cheap-source-map';
} else {
	// NOTE: setting dev tool here overrides it for both dev and prod builds
	//       also, it looks like our ancient version of Kotlin multiplatform is using webpack 5?
	//       https://webpack.js.org/configuration/devtool/
	//       allowed devtools: ^(inline-|hidden-|eval-)?(nosources-)?(cheap-(module-)?)?source-map$
	// setting devtool to undefined seems to use a default value from ... somewhere?
    //config.devtool = undefined;
    // dev builds appear to use 'eval' by default
    //config.devtool = 'eval';
    // but let's use 'source-map' for both dev and prod builds, so we can get decent error info
    config.devtool = 'source-map';
}

// disable bundle size warning
config.performance = {
    assetFilter: function (assetFilename) {
      return !assetFilename.endsWith('.js');
    },
};
