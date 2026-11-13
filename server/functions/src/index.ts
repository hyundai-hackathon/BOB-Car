import { setGlobalOptions } from "firebase-functions/v2";
import { REGION } from "./config";

setGlobalOptions({ region: REGION, maxInstances: 5 });
