import {Config} from '../../../config/config';
import { Component, Input } from '@angular/core';
import { SpecmateDataService } from "../../../services/data/specmate-data.service";

@Component({
    selector:'navigation-bar',
    moduleId:module.id,
    templateUrl: 'navigation-bar.component.html'
})
export class NavigationBar {
    constructor(private dataService: SpecmateDataService) { }

    private get version(): string {
        return Config.VERSION;
    }
}