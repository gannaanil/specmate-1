"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
var core_1 = require("@angular/core");
var truncate_pipe_1 = require("./pipes/truncate-pipe");
var TruncateModule = /** @class */ (function () {
    function TruncateModule() {
    }
    TruncateModule = __decorate([
        core_1.NgModule({
            imports: [],
            declarations: [
                // COMPONENTS IN THIS MODULE
                truncate_pipe_1.TruncatePipe
            ],
            exports: [
                // THE COMPONENTS VISIBLE TO THE OUTSIDE
                truncate_pipe_1.TruncatePipe
            ],
            providers: [],
            bootstrap: []
        })
    ], TruncateModule);
    return TruncateModule;
}());
exports.TruncateModule = TruncateModule;
//# sourceMappingURL=truncate.module.js.map