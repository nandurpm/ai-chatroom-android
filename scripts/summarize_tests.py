from pathlib import Path
import xml.etree.ElementTree as ET
reports = list(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert reports, 'No unit test reports found'
totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
for path in reports:
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, '0'))
print('UNIT TEST RESULTS:', totals)
assert totals['tests'] == 11, 'Expected all 11 unit tests'
assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
